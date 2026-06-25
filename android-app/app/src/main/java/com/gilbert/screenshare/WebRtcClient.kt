package com.gilbert.screenshare

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WebRtcClient(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val quality: Quality,
    private val frameRate: FrameRate,
    private val listener: Listener
) {
    enum class Quality(
        val id: String,
        val label: String,
        val maxCaptureSide: Int,
        val minBitrateBps: Int,
        val maxBitrateBps: Int
    ) {
        SMOOTH("smooth", "流畅", 1280, 800_000, 3_000_000),
        STANDARD("standard", "标准", 1600, 1_200_000, 6_000_000),
        HIGH("high", "高清", 1920, 2_000_000, 10_000_000),
        ULTRA("ultra", "超清", 2560, 3_000_000, 16_000_000),
        ORIGINAL("original", "原画", Int.MAX_VALUE, 8_000_000, 48_000_000);

        companion object {
            fun fromId(id: String?): Quality {
                return values().firstOrNull { it.id == id } ?: HIGH
            }
        }
    }

    enum class FrameRate(
        val id: String,
        val label: String,
        val fps: Int
    ) {
        FPS_24("24", "24fps", 24),
        FPS_30("30", "30fps", 30),
        FPS_45("45", "45fps", 45),
        FPS_60("60", "60fps", 60);

        companion object {
            fun fromId(id: String?): FrameRate {
                return values().firstOrNull { it.id == id } ?: FPS_30
            }
        }
    }

    interface Listener {
        fun onSignal(message: JSONObject)
        fun onStatus(message: String)
        fun onFatalError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioChannel: DataChannel? = null
    private var playbackCapture: AudioPlaybackCapture? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var captureSurface: Surface? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureSize = CaptureSize(0, 0)
    @Volatile
    private var resizingCapture = false
    private var stopped = false
    private var displayMetaTimer: Runnable? = null
    private val resizeCaptureTask = Runnable { resizeScreenCaptureIfNeeded() }

    fun start() {
        ensureFactoryInitialized(appContext)
        factory = createPeerConnectionFactory()
        peerConnection = createPeerConnection()

        startScreenCapture()
        createSilentAudioTrack()
        createAudioDataChannel()
        createOffer()
    }

    fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "answer" -> {
                val answer = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    message.getString("sdp")
                )
                peerConnection?.setRemoteDescription(
                    SimpleSdpObserver(onError = { listener.onStatus("设置 Answer 失败：$it") }),
                    answer
                )
            }

            "candidate" -> {
                val candidate = IceCandidate(
                    message.optString("sdpMid"),
                    message.optInt("sdpMLineIndex"),
                    message.optString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                listener.onStatus("ICE 状态：$state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                listener.onSignal(JSONObject().apply {
                    put("type", "candidate")
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }) ?: error("Failed to create PeerConnection")
    }

    private fun startScreenCapture() {
        val capturer = ScreenCapturerAndroid(
            projectionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopped) {
                        listener.onFatalError("系统已停止录屏")
                    }
                }
            }
        )
        videoCapturer = capturer
        captureSize = computeCaptureSize()

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            eglBase.eglBaseContext
        )
        val source = factory!!.createVideoSource(true)
        videoSource = source
        capturer.initialize(surfaceTextureHelper, appContext, source.capturerObserver)
        capturer.startCapture(captureSize.width, captureSize.height, frameRate.fps)
        captureSurface = Surface(surfaceTextureHelper!!.surfaceTexture)
        mediaProjection = extractMediaProjection(capturer)

        val track = factory!!.createVideoTrack(VIDEO_TRACK_ID, source)
        videoTrack = track
        videoSender = peerConnection?.addTrack(track, listOf(STREAM_ID))
        tuneVideoSender()
        registerDisplayListener()
        startDisplayMetaUpdates()
        listener.onStatus("画面采集：${captureSize.width}x${captureSize.height} @${frameRate.fps}fps · ${quality.label}")
    }

    private fun startDisplayMetaUpdates() {
        val task = object : Runnable {
            override fun run() {
                if (stopped) return
                val (width, height) = currentDisplaySize()
                if (captureSize.width > 0 && computeCaptureSize(width, height) != captureSize) {
                    scheduleCaptureResize()
                }
                sendDisplayMeta(width, height)
                mainHandler.postDelayed(this, DISPLAY_META_INTERVAL_MS)
            }
        }
        displayMetaTimer = task
        mainHandler.post(task)
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val manager = appContext.getSystemService(DisplayManager::class.java)
        displayManager = manager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || stopped) return
                scheduleCaptureResize()
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, mainHandler)
    }

    private fun unregisterDisplayListener() {
        mainHandler.removeCallbacks(resizeCaptureTask)
        val listener = displayListener
        if (listener != null) {
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        displayManager = null
    }

    private fun scheduleCaptureResize() {
        mainHandler.removeCallbacks(resizeCaptureTask)
        mainHandler.postDelayed(resizeCaptureTask, DISPLAY_RESIZE_DEBOUNCE_MS)
    }

    private fun resizeScreenCaptureIfNeeded() {
        if (stopped || resizingCapture) return
        val capturer = videoCapturer ?: return
        val helper = surfaceTextureHelper ?: return
        val nextSize = computeCaptureSize()
        if (nextSize == captureSize) {
            sendDisplayMeta()
            return
        }

        resizingCapture = true
        helper.handler.post {
            var nextSurface: Surface? = null
            try {
                if (stopped) return@post
                val display = getCapturerVirtualDisplay(capturer)
                if (display == null) {
                    listener.onStatus("画面尺寸更新失败：采集显示区不可用")
                    return@post
                }

                setCapturerIntField(capturer, "width", nextSize.width)
                setCapturerIntField(capturer, "height", nextSize.height)
                nextSurface = Surface(helper.surfaceTexture)
                val oldSurface = captureSurface
                display.setSurface(null)
                setHelperTextureSize(helper, nextSize)
                display.resize(nextSize.width, nextSize.height, WEBRTC_SCREEN_DPI)
                display.setSurface(nextSurface)
                captureSurface = nextSurface
                nextSurface = null
                oldSurface?.release()
                captureSize = nextSize
                helper.forceFrame()
                listener.onStatus("画面已适配：${nextSize.width}x${nextSize.height} @${frameRate.fps}fps")
                sendDisplayMeta()
            } catch (error: Throwable) {
                nextSurface?.release()
                if (!stopped) {
                    listener.onStatus("画面尺寸更新失败：${error.message}")
                }
            } finally {
                resizingCapture = false
            }
        }
    }

    private fun sendDisplayMeta() {
        val (width, height) = currentDisplaySize()
        sendDisplayMeta(width, height)
    }

    private fun sendDisplayMeta(width: Int, height: Int) {
        listener.onSignal(JSONObject().apply {
            put("type", "display-meta")
            put("width", width)
            put("height", height)
            put("orientation", if (width >= height) "landscape" else "portrait")
        })
    }

    private fun createSilentAudioTrack() {
        val source = factory!!.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory!!.createAudioTrack(AUDIO_TRACK_ID, source)
        audioTrack = track
        track.setEnabled(false)
        peerConnection?.addTrack(track, listOf(STREAM_ID))
    }

    private fun createAudioDataChannel() {
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        val channel = peerConnection?.createDataChannel(AUDIO_CHANNEL_LABEL, init)
        audioChannel = channel
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    listener.onStatus("声音通道已打开")
                    startPlaybackCaptureWhenReady()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) = Unit
        })
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(
            SimpleSdpObserver(
                onCreate = { offer ->
                    peerConnection?.setLocalDescription(
                        SimpleSdpObserver(
                            onSet = {
                                listener.onSignal(JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", offer.description)
                                })
                                listener.onStatus("已发送 Offer")
                            },
                            onError = { listener.onStatus("设置 Offer 失败：$it") }
                        ),
                        offer
                    )
                },
                onError = { listener.onStatus("创建 Offer 失败：$it") }
            ),
            constraints
        )
    }

    private fun startPlaybackCaptureWhenReady() {
        mainHandler.postDelayed({
            val projection = mediaProjection
            if (playbackCapture != null || stopped) return@postDelayed
            if (projection == null) {
                listener.onStatus("系统声音不可用：WebRTC 未暴露 MediaProjection")
                return@postDelayed
            }

            playbackCapture = AudioPlaybackCapture(
                mediaProjection = projection,
                onPcmData = { bytes -> sendAudio(bytes) },
                onStatus = { listener.onStatus(it) }
            ).also { it.start() }
        }, 500)
    }

    private fun extractMediaProjection(capturer: VideoCapturer): MediaProjection? {
        return runCatching {
            val field = capturer.javaClass.getDeclaredField("mediaProjection")
            field.isAccessible = true
            field.get(capturer) as? MediaProjection
        }.onFailure {
            listener.onStatus("系统声音初始化失败：${it.message}")
        }.getOrNull()
    }

    private fun sendAudio(bytes: ByteArray) {
        val channel = audioChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        if (channel.bufferedAmount() > MAX_AUDIO_BUFFERED_AMOUNT) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    private fun computeCaptureSize(): CaptureSize {
        val (rawWidth, rawHeight) = currentDisplaySize()
        return computeCaptureSize(rawWidth, rawHeight)
    }

    private fun computeCaptureSize(rawWidth: Int, rawHeight: Int): CaptureSize {
        val maxSide = max(rawWidth, rawHeight).coerceAtLeast(1)
        val scale = min(1f, quality.maxCaptureSide.toFloat() / maxSide)
        return CaptureSize(
            width = even((rawWidth * scale).roundToInt()),
            height = even((rawHeight * scale).roundToInt())
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplaySize(): Pair<Int, Int> {
        val display = appContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = appContext.getSystemService(WindowManager::class.java)
                .maximumWindowMetrics
                .bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val metrics = appContext.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun tuneVideoSender() {
        val sender = videoSender ?: return
        val parameters = sender.parameters ?: return
        val encodings = parameters.encodings
        if (encodings.isNullOrEmpty()) return
        encodings[0].maxBitrateBps = quality.maxBitrateBps
        encodings[0].minBitrateBps = quality.minBitrateBps
        encodings[0].maxFramerate = frameRate.fps
        sender.setParameters(parameters)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        unregisterDisplayListener()
        displayMetaTimer?.let { mainHandler.removeCallbacks(it) }
        displayMetaTimer = null
        playbackCapture?.stop()
        playbackCapture = null
        audioChannel?.dispose()
        audioChannel = null
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        videoSender = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        factory?.dispose()
        factory = null
        captureSurface?.release()
        captureSurface = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        eglBase.release()
    }

    private fun even(value: Int): Int = max(2, value - value % 2)

    private fun getCapturerVirtualDisplay(capturer: VideoCapturer): VirtualDisplay? {
        return runCatching {
            val field = capturer.javaClass.getDeclaredField("virtualDisplay")
            field.isAccessible = true
            field.get(capturer) as? VirtualDisplay
        }.getOrNull()
    }

    private fun setCapturerIntField(capturer: VideoCapturer, fieldName: String, value: Int) {
        runCatching {
            val field = capturer.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.setInt(capturer, value)
        }
    }

    private fun setHelperTextureSize(helper: SurfaceTextureHelper, size: CaptureSize) {
        helper.surfaceTexture.setDefaultBufferSize(size.width, size.height)
        setHelperIntField(helper, "textureWidth", size.width)
        setHelperIntField(helper, "textureHeight", size.height)
    }

    private fun setHelperIntField(helper: SurfaceTextureHelper, fieldName: String, value: Int) {
        runCatching {
            val field = helper.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.setInt(helper, value)
        }
    }

    private data class CaptureSize(val width: Int, val height: Int)

    companion object {
        private const val STREAM_ID = "android-screen"
        private const val VIDEO_TRACK_ID = "screen-video"
        private const val AUDIO_TRACK_ID = "silent-audio"
        private const val AUDIO_CHANNEL_LABEL = "system-audio-pcm"
        private const val MAX_AUDIO_BUFFERED_AMOUNT = 1_000_000L
        private const val DISPLAY_META_INTERVAL_MS = 500L
        private const val DISPLAY_RESIZE_DEBOUNCE_MS = 300L
        private const val WEBRTC_SCREEN_DPI = 400

        @Volatile
        private var initialized = false

        private fun ensureFactoryInitialized(context: Context) {
            if (initialized) return
            synchronized(WebRtcClient::class.java) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }
}

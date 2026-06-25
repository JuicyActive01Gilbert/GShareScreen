package com.gilbert.screenshare

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class UsbDirectStreamer(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val pairCode: String,
    private val quality: WebRtcClient.Quality,
    private val frameRate: WebRtcClient.FrameRate,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onFatalError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var playbackCapture: AudioPlaybackCapture? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureSize = CaptureSize(0, 0)
    private var lastFrameAt = 0L
    private var lastMetaAt = 0L
    private var resizingOutput = false
    private val resizeOutputTask = Runnable { resizeScreenOutputIfNeeded() }

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        val request = Request.Builder().url(USB_DIRECT_WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().apply {
                    put("type", "usb-start")
                    put("code", pairCode)
                    put("quality", quality.id)
                }.toString())
                mainHandler.post {
                    listener.onStatus("USB 直连已建立，正在采集画面")
                    runCatching { startCapture() }
                        .onFailure { listener.onFatalError("USB 采集启动失败：${it.message}") }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) {
                    mainHandler.post { listener.onFatalError("USB 直连已断开") }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped) {
                    mainHandler.post { listener.onFatalError("USB 直连失败：${t.message}") }
                }
            }
        })
    }

    private fun startCapture() {
        if (stopped) return

        val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, projectionData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopped) {
                    listener.onFatalError("系统已停止录屏")
                }
            }
        }, mainHandler)

        workerThread = HandlerThread("UsbDirectCapture").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        startScreenOutput()
        registerDisplayListener()

        playbackCapture = AudioPlaybackCapture(
            mediaProjection = projection,
            onPcmData = { sendBinary(TYPE_AUDIO, it) },
            onStatus = { listener.onStatus(it) }
        ).also { it.start() }
    }

    private fun startScreenOutput() {
        runCatching {
            val projection = mediaProjection ?: return
            val handler = workerHandler ?: return
            val nextSize = computeCaptureSize()
            captureSize = nextSize
            lastFrameAt = 0L

            val reader = createImageReader(nextSize, handler)
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "GShareUsbDirect",
                nextSize.width,
                nextSize.height,
                appContext.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            webSocket?.send(JSONObject().apply {
                put("type", "usb-status")
                put("message", "USB 画面采集：${nextSize.width}x${nextSize.height} @${usbFps()}fps")
            }.toString())
            sendDisplayMeta(force = true)
        }.onFailure {
            listener.onFatalError("USB 画面采集失败：${it.message}")
        }
    }

    private fun createImageReader(size: CaptureSize, handler: Handler): ImageReader {
        return ImageReader.newInstance(
            size.width,
            size.height,
            PixelFormat.RGBA_8888,
            IMAGE_BUFFER_COUNT
        ).also { reader ->
            reader.setOnImageAvailableListener({ handleImageAvailable(it) }, handler)
        }
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
                scheduleOutputResize()
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, mainHandler)
    }

    private fun unregisterDisplayListener() {
        workerHandler?.removeCallbacks(resizeOutputTask)
        val listener = displayListener
        if (listener != null) {
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        displayManager = null
    }

    private fun scheduleOutputResize() {
        workerHandler?.removeCallbacks(resizeOutputTask)
        workerHandler?.postDelayed(resizeOutputTask, DISPLAY_RESIZE_DEBOUNCE_MS)
    }

    private fun resizeScreenOutputIfNeeded() {
        if (stopped || resizingOutput) return
        val display = virtualDisplay ?: return
        val handler = workerHandler ?: return
        val nextSize = computeCaptureSize()
        if (nextSize == captureSize) {
            sendDisplayMeta(force = true)
            return
        }

        try {
            resizingOutput = true
            var nextReader: ImageReader? = null
            runCatching {
            nextReader = createImageReader(nextSize, handler)
            val oldReader = imageReader
            display.setSurface(null)
            display.resize(
                nextSize.width,
                nextSize.height,
                    appContext.resources.displayMetrics.densityDpi
                )
                display.setSurface(nextReader!!.surface)
                imageReader = nextReader
                nextReader = null
                captureSize = nextSize
                lastFrameAt = 0L

                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()

                webSocket?.send(JSONObject().apply {
                    put("type", "usb-status")
                    put("message", "USB 画面已适配：${nextSize.width}x${nextSize.height} @${usbFps()}fps")
                }.toString())
                sendDisplayMeta(force = true)
            }.onFailure {
                nextReader?.setOnImageAvailableListener(null, null)
                nextReader?.close()
                listener.onStatus("USB 画面尺寸更新失败：${it.message}")
            }
        } finally {
            resizingOutput = false
        }
    }

    private fun releaseScreenOutput() {
        workerHandler?.removeCallbacks(resizeOutputTask)
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.setSurface(null) }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureSize = CaptureSize(0, 0)
    }

    private fun handleImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 1000L / usbFps()) return
            val socket = webSocket ?: return
            if (socket.queueSize() > MAX_SOCKET_QUEUE_BYTES) return
            lastFrameAt = now
            sendDisplayMeta(force = false)

            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmapWidth = image.width + rowPadding / pixelStride

            val paddedBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val frameBitmap = if (bitmapWidth == image.width) {
                paddedBitmap
            } else {
                Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height).also {
                    paddedBitmap.recycle()
                }
            }

            val output = ByteArrayOutputStream()
            frameBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality(), output)
            frameBitmap.recycle()
            sendBinary(TYPE_VIDEO, output.toByteArray())
        } catch (error: Throwable) {
            if (!stopped) {
                listener.onStatus("USB 画面编码失败：${error.message}")
            }
        } finally {
            image.close()
        }
    }

    private fun sendBinary(type: Byte, payload: ByteArray) {
        val socket = webSocket ?: return
        if (socket.queueSize() > MAX_SOCKET_QUEUE_BYTES) return
        val frame = ByteArray(payload.size + 1)
        frame[0] = type
        payload.copyInto(frame, destinationOffset = 1)
        socket.send(frame.toByteString())
    }

    private fun sendDisplayMeta(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastMetaAt < DISPLAY_META_INTERVAL_MS) return
        lastMetaAt = now

        val (width, height) = currentDisplaySize()
        if (captureSize.width > 0 && computeCaptureSize(width, height) != captureSize) {
            scheduleOutputResize()
        }
        webSocket?.send(JSONObject().apply {
            put("type", "usb-display")
            put("width", width)
            put("height", height)
            put("orientation", if (width >= height) "landscape" else "portrait")
        }.toString())
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

    fun stop() {
        if (stopped) return
        stopped = true
        unregisterDisplayListener()
        playbackCapture?.stop()
        playbackCapture = null
        releaseScreenOutput()
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        webSocket?.close(1000, "service stopped")
        webSocket = null
        client.dispatcher.executorService.shutdown()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    private fun jpegQuality(): Int {
        return when (quality) {
            WebRtcClient.Quality.SMOOTH -> 52
            WebRtcClient.Quality.STANDARD -> 64
            WebRtcClient.Quality.HIGH -> 76
            WebRtcClient.Quality.ULTRA -> 84
            WebRtcClient.Quality.ORIGINAL -> 96
        }
    }

    private fun usbFps(): Int {
        return frameRate.fps
    }

    private fun even(value: Int): Int = max(2, value - value % 2)

    private data class CaptureSize(val width: Int, val height: Int)

    companion object {
        private const val USB_DIRECT_WS_URL = "ws://127.0.0.1:3767"
        private const val IMAGE_BUFFER_COUNT = 2
        private const val MAX_SOCKET_QUEUE_BYTES = 8_000_000L
        private const val DISPLAY_META_INTERVAL_MS = 500L
        private const val DISPLAY_RESIZE_DEBOUNCE_MS = 300L
        private const val TYPE_VIDEO: Byte = 1
        private const val TYPE_AUDIO: Byte = 2
    }
}

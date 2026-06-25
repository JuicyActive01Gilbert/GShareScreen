package com.gilbert.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject

class ScreenShareService : Service() {
    private var signalingClient: SignalingClient? = null
    private var webRtcClient: WebRtcClient? = null
    private var usbDirectStreamer: UsbDirectStreamer? = null
    private var floatingWindow: FloatingWindowController? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        val signalingUrl = intent.getStringExtra(EXTRA_SIGNALING_URL).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = getProjectionData(intent)
        val quality = WebRtcClient.Quality.fromId(intent.getStringExtra(EXTRA_QUALITY))
        val frameRate = WebRtcClient.FrameRate.fromId(intent.getStringExtra(EXTRA_FRAME_RATE))
        val pairCode = intent.getStringExtra(EXTRA_PAIR_CODE).orEmpty()

        if (signalingUrl.isBlank() || projectionData == null) {
            Log.e(TAG, "Missing signaling URL or MediaProjection data.")
            stopSelf()
            return
        }

        stopping = false
        startAsForeground("正在连接电脑端")
        stopClients()
        ensureFloatingWindow()

        if (signalingUrl.startsWith("usb://")) {
            startUsbDirectSession(resultCode, projectionData, pairCode, quality, frameRate)
            return
        }

        webRtcClient = WebRtcClient(
            context = this,
            resultCode = resultCode,
            projectionData = projectionData,
            quality = quality,
            frameRate = frameRate,
            listener = object : WebRtcClient.Listener {
                override fun onSignal(message: JSONObject) {
                    signalingClient?.send(message)
                }

                override fun onStatus(message: String) {
                    updateNotification(message)
                    Log.i(TAG, message)
                }

                override fun onFatalError(message: String) {
                    Log.e(TAG, message)
                    updateNotification(message)
                    stopSession()
                }
            }
        )

        signalingClient = SignalingClient(
            url = signalingUrl,
            listener = object : SignalingClient.Listener {
                override fun onOpen() {
                    updateNotification("已连接电脑端，正在建立高清传输")
                    runCatching { webRtcClient?.start() }
                        .onFailure {
                            Log.e(TAG, "WebRTC start failed", it)
                            updateNotification("WebRTC 启动失败：${it.message}")
                            stopSession()
                        }
                }

                override fun onMessage(message: JSONObject) {
                    webRtcClient?.handleSignal(message)
                }

                override fun onClosed(reason: String) {
                    if (!stopping) {
                        updateNotification("信令已断开：$reason")
                        stopSession()
                    }
                }

                override fun onError(error: String) {
                    updateNotification("信令错误：$error")
                    Log.e(TAG, error)
                    if (!stopping) stopSession()
                }
            }
        ).also { it.connect() }
    }

    private fun startUsbDirectSession(
        resultCode: Int,
        projectionData: Intent,
        pairCode: String,
        quality: WebRtcClient.Quality,
        frameRate: WebRtcClient.FrameRate
    ) {
        usbDirectStreamer = UsbDirectStreamer(
            context = this,
            resultCode = resultCode,
            projectionData = projectionData,
            pairCode = pairCode,
            quality = quality,
            frameRate = frameRate,
            listener = object : UsbDirectStreamer.Listener {
                override fun onStatus(message: String) {
                    updateNotification(message)
                    Log.i(TAG, message)
                }

                override fun onFatalError(message: String) {
                    Log.e(TAG, message)
                    updateNotification(message)
                    stopSession()
                }
            }
        ).also { it.start() }
    }

    private fun getProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)
        }
    }

    private fun stopSession() {
        if (stopping) return
        stopping = true
        stopClients()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopClients() {
        floatingWindow?.hide()
        floatingWindow = null
        signalingClient?.close()
        signalingClient = null
        webRtcClient?.stop()
        webRtcClient = null
        usbDirectStreamer?.stop()
        usbDirectStreamer = null
    }

    private fun ensureFloatingWindow() {
        floatingWindow = FloatingWindowController(
            context = this,
            onStopRequested = { stopSession() },
            onCloseRequested = { floatingWindow = null }
        ).also { it.show() }
    }

    private fun startAsForeground(content: String) {
        createNotificationChannel()
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_stat_g_share)
            .setContentTitle("G享屏正在共享")
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopClients()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gilbert.screenshare.START"
        const val ACTION_STOP = "com.gilbert.screenshare.STOP"
        const val EXTRA_SIGNALING_URL = "signaling_url"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FRAME_RATE = "frame_rate"
        const val EXTRA_PAIR_CODE = "pair_code"

        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 4101
    }
}

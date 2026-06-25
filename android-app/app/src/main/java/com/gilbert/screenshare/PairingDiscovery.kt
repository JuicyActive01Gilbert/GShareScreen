package com.gilbert.screenshare

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PairingDiscovery {
    interface Callback {
        fun onFound(wsUrl: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun find(pairCode: String, callback: Callback) {
        thread(name = "PairingDiscovery") {
            Log.i(TAG, "开始查找 PC，优先尝试 USB 直连")
            runCatching { probeUsb(pairCode).getOrElse { discover(pairCode) } }
                .onSuccess { wsUrl ->
                    Log.i(TAG, "发现 PC：$wsUrl")
                    mainHandler.post { callback.onFound(wsUrl) }
                }
                .onFailure { error ->
                    Log.e(TAG, "查找 PC 失败：${error.message}")
                    mainHandler.post { callback.onError(error.message ?: "Discovery failed") }
                }
        }
    }

    private fun discover(pairCode: String): String {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 800

            val request = JSONObject()
                .put("type", "screen-share-discover")
                .put("code", pairCode)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val packet = DatagramPacket(
                request,
                request.size,
                InetAddress.getByName(BROADCAST_ADDRESS),
                DISCOVERY_PORT
            )

            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            val receiveBuffer = ByteArray(4096)
            var nextBroadcastAt = 0L

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextBroadcastAt) {
                    socket.send(packet)
                    nextBroadcastAt = now + BROADCAST_INTERVAL_MS
                }

                val response = DatagramPacket(receiveBuffer, receiveBuffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val text = String(response.data, 0, response.length, Charsets.UTF_8)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (json.optString("type") != "screen-share-peer") continue
                if (json.optString("code") != pairCode) continue

                val wsUrl = json.optString("wsUrl")
                if (wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")) {
                    return wsUrl
                }
            }
        }

        error("没有发现对应配对码的 PC 端")
    }

    private fun probeUsb(pairCode: String): Result<String> {
        val client = OkHttpClient.Builder()
            .callTimeout(USB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val latch = CountDownLatch(1)
        var result: Result<String> = Result.failure(IllegalStateException("USB 未连接"))
        var socket: WebSocket? = null

        val request = Request.Builder().url(USB_PROBE_URL).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "USB 探测通道已打开")
                webSocket.send(
                    JSONObject()
                        .put("type", "usb-probe")
                        .put("code", pairCode)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json?.optString("type") == "usb-peer") {
                    val url = json.optString("url")
                    result = if (url.startsWith("usb://")) {
                        Result.success(url)
                    } else {
                        Result.success("usb://127.0.0.1:$USB_DIRECT_PORT?code=$pairCode")
                    }
                    webSocket.close(1000, "probe done")
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "USB 探测失败：${t.message}")
                result = Result.failure(t)
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        val completed = latch.await(USB_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.i(TAG, "USB 探测超时")
            socket.cancel()
        }
        client.dispatcher.executorService.shutdown()
        return result
    }

    companion object {
        private const val DISCOVERY_PORT = 3766
        private const val USB_DIRECT_PORT = 3767
        private const val USB_PROBE_URL = "ws://127.0.0.1:3767"
        private const val BROADCAST_ADDRESS = "255.255.255.255"
        private const val DISCOVERY_TIMEOUT_MS = 8000L
        private const val BROADCAST_INTERVAL_MS = 500L
        private const val USB_PROBE_TIMEOUT_MS = 900L
        private const val TAG = "PairingDiscovery"
    }
}

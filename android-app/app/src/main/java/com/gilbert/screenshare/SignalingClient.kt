package com.gilbert.screenshare

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class SignalingClient(
    private val url: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onMessage(message: JSONObject)
        fun onClosed(reason: String)
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    @Volatile
    private var closing = false

    fun connect() {
        closing = false
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post { listener.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    runCatching { JSONObject(text) }
                        .onSuccess { listener.onMessage(it) }
                        .onFailure { listener.onError("信令消息解析失败：${it.message}") }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closing) {
                    mainHandler.post { listener.onClosed(reason.ifBlank { "closed" }) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closing) {
                    mainHandler.post { listener.onError(t.message ?: "websocket failure") }
                }
            }
        })
    }

    fun send(message: JSONObject) {
        webSocket?.send(message.toString())
    }

    fun close() {
        closing = true
        webSocket?.close(1000, "service stopped")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}

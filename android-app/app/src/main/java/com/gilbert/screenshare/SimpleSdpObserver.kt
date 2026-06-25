package com.gilbert.screenshare

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SimpleSdpObserver(
    private val onCreate: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) {
        if (description != null) onCreate(description)
    }

    override fun onSetSuccess() {
        onSet()
    }

    override fun onCreateFailure(error: String?) {
        onError(error ?: "SDP create failed")
    }

    override fun onSetFailure(error: String?) {
        onError(error ?: "SDP set failed")
    }
}

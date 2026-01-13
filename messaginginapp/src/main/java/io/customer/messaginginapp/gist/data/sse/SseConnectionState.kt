package io.customer.messaginginapp.gist.data.sse

internal enum class SseConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

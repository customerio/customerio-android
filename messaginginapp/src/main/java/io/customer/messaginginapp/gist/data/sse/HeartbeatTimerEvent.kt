package io.customer.messaginginapp.gist.data.sse

/**
 * Events emitted by HeartbeatTimer when timer state changes.
 */
internal sealed class HeartbeatTimerEvent {
    /**
     * Emitted when the heartbeat timer expires, indicating the server
     * has stopped sending heartbeats within the expected timeframe.
     */
    object Timeout : HeartbeatTimerEvent()
}

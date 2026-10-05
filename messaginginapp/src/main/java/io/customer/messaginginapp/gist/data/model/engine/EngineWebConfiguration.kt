package io.customer.messaginginapp.gist.data.model.engine

internal data class EngineWebConfiguration(
    // Null when a public key is set, so the renderer authenticates with the key instead
    val siteId: String?,
    val dataCenter: String,
    val messageId: String,
    val instanceId: String,
    val endpoint: String,
    val livePreview: Boolean = false,
    val properties: Map<String, Any?>? = null,
    val customAttributes: Map<String, Any>? = null,
    val colorScheme: String? = null,
    // Public (wk_) API key, the renderer uses it as `?key=` when set
    val key: String? = null
)

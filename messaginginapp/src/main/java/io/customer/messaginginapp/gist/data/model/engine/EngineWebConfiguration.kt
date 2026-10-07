package io.customer.messaginginapp.gist.data.model.engine

internal data class EngineWebConfiguration(
    // Empty when no site ID is configured, which is fine when a public key is set
    val siteId: String,
    val dataCenter: String,
    val messageId: String,
    val instanceId: String,
    val endpoint: String,
    val livePreview: Boolean = false,
    val properties: Map<String, Any?>? = null,
    val customAttributes: Map<String, Any>? = null,
    val colorScheme: String? = null,
    // Public (wk_) API key, the renderer sends it as `?key=` alongside siteId when set
    val key: String? = null
)

package io.customer.messaginginapp.gist.data.model.engine

import io.customer.base.internal.InternalCustomerIOApi

@InternalCustomerIOApi
data class EngineWebConfiguration(
    val siteId: String,
    val dataCenter: String,
    val messageId: String,
    val instanceId: String,
    val endpoint: String,
    val livePreview: Boolean = false,
    val properties: Map<String, Any?>? = null
)

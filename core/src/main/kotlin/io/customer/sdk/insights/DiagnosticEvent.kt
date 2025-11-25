package io.customer.sdk.insights

import io.customer.sdk.core.util.CioLogLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DiagnosticEvent(
    val name: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val timestamp: Long = System.currentTimeMillis(),
    val level: CioLogLevel = CioLogLevel.INFO
)

package io.customer.insights

import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.insights.DiagnosticEvent
import io.customer.sdk.insights.DiagnosticsUploader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Implementation of DiagnosticsUploader that sends diagnostic events to Customer.io server
 * via HTTP POST request.
 *
 * @param endpoint The server endpoint for diagnostics (default: "/diagnostics")
 * @param authHeaderProvider Optional function to provide authentication headers.
 *                          Called for each upload request. Return null or empty map if no auth needed.
 */
internal class DiagnosticsHttpUploader(
    private val endpoint: String = "/diagnostics",
    private val logger: Logger = SDKComponent.logger,
    private val authHeaderProvider: (() -> Map<String, String>?)? = null
) : DiagnosticsUploader {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val globalPreferenceStore: GlobalPreferenceStore
        get() = SDKComponent.android().globalPreferenceStore

    override fun upload(events: List<DiagnosticEvent>) {
        if (events.isEmpty()) return

        try {
            val settings = globalPreferenceStore.getSettings()
            if (settings == null) {
                logger.debug("Cannot upload diagnostics: Settings not available")
                return
            }

            val apiHost = settings.apiHost
            val cleanedPath = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
            val urlString = "https://$apiHost$cleanedPath"

            val requestBody = json.encodeToString(mapOf("events" to events))

            val connection = URL(urlString).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")

                // Add authentication headers if provided
                authHeaderProvider?.invoke()?.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                connection.doOutput = true

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    logger.debug("Successfully uploaded ${events.size} diagnostic events")
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    logger.error("Failed to upload diagnostics: HTTP $responseCode - $errorBody")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            logger.error("Failed to upload diagnostics", throwable = e)
        } catch (e: Exception) {
            logger.error("Unexpected error uploading diagnostics", throwable = e)
        }
    }
}

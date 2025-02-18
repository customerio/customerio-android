package io.customer.messagingpush.network

import android.util.Base64
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.GlobalPreferenceStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class HttpRequestParams(
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

internal interface HttpClient {
    /**
     * Performs a POST request to [params.url] with [params.headers] and [params.body].
     *
     * @param params The request parameters (URL, headers, body).
     * @param onComplete Callback invoked with a `Result<String>`:
     *   - `Result.success(responseBody)` for 2xx response codes
     *   - `Result.failure(exception)` for network errors or non-2xx codes
     */
    fun request(params: HttpRequestParams, onComplete: (Result<String>) -> Unit)
}

internal class HttpClientImpl : HttpClient {

    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 10_000
    private val globalPreferenceStore: GlobalPreferenceStore
        get() = SDKComponent.android().globalPreferenceStore
    private val client: Client
        get() = SDKComponent.android().client
    private val dispatcher: DispatchersProvider
        get() = SDKComponent.dispatchersProvider

    override fun request(params: HttpRequestParams, onComplete: (Result<String>) -> Unit) {
        // Launch a coroutine on our IO dispatcher
        CoroutineScope(dispatcher.background).launch {
            val result = doNetworkRequest(params)
            // If you want to call onComplete on the same IO thread, just invoke it here.
            // If you prefer to call it on the main thread, do:
            // withContext(Dispatchers.Main) { onComplete(result) }
            onComplete(result)
        }
    }

    private fun doNetworkRequest(params: HttpRequestParams): Result<String> {
        val settings = globalPreferenceStore.getSettings() ?: return Result.failure(IllegalStateException("Setting not available"))
        val apiHost = settings.apiHost
        val writeKey = settings.writeKey

        // Ensure we have exactly one slash
        val cleanedPath = if (params.path.startsWith("/")) params.path else "/${params.path}"
        val urlString = "https://$apiHost$cleanedPath"

        val connection = try {
            val urlObj = URL(urlString)
            urlObj.openConnection() as HttpURLConnection
        } catch (e: MalformedURLException) {
            return Result.failure(IOException("Malformed URL: $urlString", e))
        } catch (e: IOException) {
            return Result.failure(e)
        }

        return try {
            // Configure the connection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "POST"
            connection.setRequestProperty("User-Agent", client.toString())

            // Authorization: Basic <base64("writeKey:")>
            val base64Value = Base64.encodeToString(
                "$writeKey:".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $base64Value")

            // Additional headers
            params.headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // Write the body if present
            params.body?.let { requestBody ->
                connection.doOutput = true
                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray())
                }
            }

            // Execute
            val responseCode = connection.responseCode
            val inputStream = try {
                connection.inputStream
            } catch (e: IOException) {
                connection.errorStream
            }
            val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode in 200..299) {
                Result.success(responseBody)
            } else {
                Result.failure(IOException("HTTP $responseCode: $responseBody"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }
}

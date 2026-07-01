package io.customer.sdk.core.network

import android.net.Uri
import android.util.Base64
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.GlobalPreferenceStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

@InternalCustomerIOApi
data class HttpRequestParams(
    val path: String,
    val method: HttpMethod = HttpMethod.POST,
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

@InternalCustomerIOApi
enum class HttpMethod { GET, POST }

/** HTTP client for Customer.io API calls with SDK authentication. */
@InternalCustomerIOApi
interface CustomerIOHttpClient {
    /**
     * Performs an HTTP request per [params]. Returns `Result.success(body)`
     * for 2xx and `Result.failure(exception)` for network errors or non-2xx.
     */
    suspend fun request(params: HttpRequestParams): Result<String>
}

internal class CustomerIOHttpClientImpl : CustomerIOHttpClient {

    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 10_000
    private val globalPreferenceStore: GlobalPreferenceStore
        get() = SDKComponent.android().globalPreferenceStore
    private val client: Client
        get() = SDKComponent.android().client

    override suspend fun request(params: HttpRequestParams): Result<String> {
        return doNetworkRequest(params)
    }

    private fun doNetworkRequest(params: HttpRequestParams): Result<String> {
        val settings = globalPreferenceStore.getSettings() ?: return Result.failure(IllegalStateException("Setting not available"))
        val apiHost = settings.apiHost
        val writeKey = settings.writeKey

        // `apiHost` already includes the API version prefix (e.g.,
        // "cdp.customer.io/v1"), so `Uri.Builder().authority()` can't be used
        // here — it would percent-encode the embedded `/`. Compose the base URL
        // first, then layer query params on top via `buildUpon`.
        val cleanedPath = if (params.path.startsWith("/")) params.path else "/${params.path}"
        val urlString = Uri.parse("https://$apiHost$cleanedPath")
            .buildUpon()
            .apply {
                params.queryParams.forEach { (k, v) -> appendQueryParameter(k, v) }
            }
            .build()
            .toString()

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
            connection.requestMethod = params.method.name
            connection.setRequestProperty("User-Agent", client.toString())

            // Authorization: Basic <base64("writeKey:")>
            val base64Value = Base64.encodeToString(
                "$writeKey:".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $base64Value")

            // Additional headers
            for ((key, value) in params.headers) {
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

package io.customer.sdk.core.network

import android.util.Base64
import androidx.core.net.toUri
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
    val body: String? = null,
    /**
     * API version for this request, replacing whatever `apiHost` carries. Null keeps the configured
     * one, so a single endpoint can move version without moving `/track` with it.
     */
    val apiVersion: String? = null
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
        val writeKey = settings.writeKey
        val urlString = composeUrl(settings.apiHost, params)

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
                Result.failure(HttpRequestFailure(responseCode, responseBody))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }
}

private fun String.isApiVersionSegment(): Boolean =
    length >= 2 && this[0] == 'v' && drop(1).all(Char::isDigit)

/**
 * Splits the version segment `apiHost` conflates with the host: `cdp.customer.io/v1` becomes
 * `cdp.customer.io` and `v1`. A host carrying no version answers null, never a guessed default.
 *
 * Segments rather than a match on the tail, because `apiHost` is customer-supplied: a stray or
 * doubled slash arrives verbatim, and `cdp.customer.io//v1` would otherwise leave a trailing slash
 * on the host and compose `//v2`. Only a segment AFTER a separator counts, so a host whose NAME
 * carries a version (`v1.example.com`) keeps it.
 */
internal fun String.splitApiVersion(): Pair<String, String?> {
    val segments = split('/').filter(String::isNotEmpty)
    val version = segments.takeIf { it.size > 1 }?.last()?.takeIf(String::isApiVersionSegment)
    val host = if (version == null) segments else segments.dropLast(1)
    return host.joinToString("/") to version
}

/**
 * Full request URL for [params] against [apiHost].
 *
 * The version defaults to whatever `apiHost` carried and is replaced only when the request names
 * its own, so one endpoint can move to v2 while `/track` stays on the configured version. Replaced
 * rather than appended: a host already on v3 must not compose `/v3/v2`.
 *
 * Extracted so that swap can be asserted on the URL actually sent. `apiHost` embeds the version, so
 * `Uri.Builder().authority()` cannot be used here: it would percent-encode the embedded `/`.
 * Compose the base string first, then layer query params on top.
 */
internal fun composeUrl(apiHost: String, params: HttpRequestParams): String {
    val (host, hostVersion) = apiHost.splitApiVersion()
    val version = params.apiVersion ?: hostVersion
    val base = if (version == null) host else "$host/$version"
    val cleanedPath = if (params.path.startsWith("/")) params.path else "/${params.path}"
    return "https://$base$cleanedPath".toUri()
        .buildUpon()
        .apply { params.queryParams.forEach { (k, v) -> appendQueryParameter(k, v) } }
        .build()
        .toString()
}

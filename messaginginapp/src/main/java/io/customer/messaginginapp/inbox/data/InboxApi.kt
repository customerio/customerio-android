package io.customer.messaginginapp.inbox.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.sdk.core.di.SDKComponent
import java.util.concurrent.TimeUnit
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

/**
 * Retrofit service for the two inbox GET endpoints.
 *
 * GET /api/v1/templates returns the raw template registry as opaque JSON. We
 * deliberately surface it as a JSON string ([Response] body decoded by the
 * caller) so the gist layer stays Jist-agnostic — the inbox/Jist layer decodes
 * it into Jist types, not us.
 *
 * GET /api/v1/branding returns branding theme tokens + patterns.
 */
internal interface InboxService {
    @GET("/api/v1/templates")
    suspend fun fetchTemplates(): Response<JsonObject>

    @GET("/api/v1/branding")
    suspend fun fetchBranding(): Response<JsonObject>
}

/**
 * Thin client wrapper for the inbox fetch path.
 *
 * Reuses the existing gist OkHttp client (its HTTP cache + 304/network-response
 * interceptor + common auth headers) rather than owning a dedicated client: the
 * two GETs share the same workspace-scoped, URL-keyed HTTP caching as the queue
 * poll. We derive a [OkHttpClient.newBuilder] variant only to apply the 5s
 * per-call timeout ([callTimeout]); this preserves the underlying connection
 * pool, dispatcher, cache, and interceptors of the gist client.
 */
internal class InboxApi(
    private val gistQueue: GistQueue = SDKComponent.gistQueue
) {
    private val gson = Gson()

    private val service: InboxService by lazy { createService() }

    private fun createService(): InboxService {
        // Reuse the gist client (cache + 304 interceptor + auth headers); newBuilder
        // shares its pool/cache/interceptors and just layers on the per-call 5s timeout.
        val httpClient = gistQueue.httpClient.newBuilder()
            .callTimeout(INBOX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(gistQueue.baseUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient)
            .build()
            .create(InboxService::class.java)
    }

    /**
     * Fetches the raw templates registry as an opaque JSON string so the inbox/Jist
     * layer can decode it; the gist layer never interprets template contents.
     */
    suspend fun fetchTemplatesRaw(): String {
        val response = service.fetchTemplates()
        if (!response.isSuccessful) {
            throw InboxFetchException("templates request failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: throw InboxFetchException("templates response had empty body")
        return body.toString()
    }

    /**
     * Fetches branding and maps the raw JSON into the transport [Branding] model.
     */
    suspend fun fetchBranding(): Branding {
        val response = service.fetchBranding()
        if (!response.isSuccessful) {
            throw InboxFetchException("branding request failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: throw InboxFetchException("branding response had empty body")
        return parseBrandingJson(body, gson)
    }

    companion object {
        const val INBOX_CALL_TIMEOUT_SECONDS = 5L
    }
}

/**
 * Pure branding parser, extracted so it is unit-testable WITHOUT network/Retrofit.
 * Tolerant of missing keys throughout (Gson leaves absent fields null).
 */
internal fun parseBrandingJson(json: JsonObject, gson: Gson): Branding {
    val theme = json.getAsJsonObject("theme")?.let { gson.toAnyMap(it) } ?: emptyMap()
    val patternsJson = json.getAsJsonObject("patterns")

    val inboxJson = patternsJson?.getAsJsonObject("inbox")
    val inboxChrome = inboxJson?.let { gson.fromJson(it, Branding.InboxChrome::class.java) }

    // patterns.modes.dark is OPTIONAL: model the whole modes block as nullable so an
    // absent dark mode stays null, never an empty map.
    val modesJson = patternsJson?.getAsJsonObject("modes")
    val modes = modesJson?.let {
        val dark = it.getAsJsonObject("dark")?.let { darkJson -> gson.toAnyMap(darkJson) }
        Branding.Modes(dark = dark)
    }

    return Branding(
        theme = theme,
        patterns = Branding.Patterns(
            inbox = inboxChrome,
            modes = modes
        )
    )
}

@Suppress("UNCHECKED_CAST")
private fun Gson.toAnyMap(json: JsonObject): Map<String, Any?> =
    fromJson(json, Map::class.java) as Map<String, Any?>

/**
 * Marker exception for inbox fetch failures so the retry/backoff layer can treat
 * them uniformly. Wrapping keeps a stable, decodable failure surface.
 */
internal class InboxFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

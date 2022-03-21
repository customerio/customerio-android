package io.customer.sdk.api.interceptors

import android.util.Base64
import io.customer.base.extenstions.filterHeaderValue
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.store.DeviceStore
import okhttp3.Interceptor
import okhttp3.Response
import java.nio.charset.StandardCharsets

internal class HeadersInterceptor(
    private val deviceStore: DeviceStore,
    private val config: CustomerIOConfig
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val requestBuilder = chain.request().newBuilder().apply {
            getHeaders().forEach { headerPair ->
                addHeader(headerPair.first, headerPair.second)
            }
        }

        return chain.proceed(requestBuilder.build())
    }

    internal fun getHeaders(): List<Pair<String, String>> {
        return listOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Authorization" to "Basic ${getBasicAuthHeaderString()}",
            "User-Agent" to deviceStore.buildUserAgent()
        ).map { headerPair ->
            headerPair.first to headerPair.second.filterHeaderValue()
        }
    }

    private fun getBasicAuthHeaderString(): String {
        val apiKey = config.apiKey
        val siteId = config.siteId
        val rawHeader = "$siteId:$apiKey"
        return Base64.encodeToString(rawHeader.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }
}

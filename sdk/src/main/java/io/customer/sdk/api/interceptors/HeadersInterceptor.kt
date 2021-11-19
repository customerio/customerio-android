package io.customer.sdk.api.interceptors

import android.util.Base64
import io.customer.sdk.CustomerIO
import okhttp3.Interceptor
import okhttp3.Response
import java.nio.charset.StandardCharsets

internal class HeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {

        val token by lazy { "Basic ${getBasicAuthHeaderString()}" }
        val userAgent by lazy { CustomerIO.instance().store.deviceStore.buildUserAgent() }

        val request = chain.request()
            .newBuilder()
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Authorization", token)
            .addHeader("User-Agent", userAgent)
            .build()
        return chain.proceed(request)
    }

    private fun getBasicAuthHeaderString(): String {
        val apiKey = CustomerIO.instance().config.apiKey
        val siteId = CustomerIO.instance().config.siteId
        val rawHeader = "$siteId:$apiKey"
        return Base64.encodeToString(rawHeader.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }
}

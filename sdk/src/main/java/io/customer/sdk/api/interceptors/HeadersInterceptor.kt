package io.customer.sdk.api.interceptors

import okhttp3.Interceptor
import okhttp3.Response

internal class HeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
            .newBuilder()
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Authorization", "")
            .addHeader("User-Agent", "CustomerIO-SDK-Android/(SdkVersion.version)")
            .build()
        return chain.proceed(request)
    }
}

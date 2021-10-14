package io.customer.sdk.di

import io.customer.sdk.api.HeadersInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

internal open class Initializers {

    private val baseClient: OkHttpClient by lazy { OkHttpClient() }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        baseClient.newBuilder().followRedirects(false)

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return buildRetrofit(
            "base-url-with-region",
            1L,
        ).create(apiClass)
    }

    private fun buildRetrofit(
        endpoint: String,
        timeout: Long,
    ): Retrofit {
        val okHttpClient = clientBuilder(timeout).build()

        return Retrofit.Builder()
            .baseUrl(endpoint)
            .client(okHttpClient)
            .build()
    }

    protected open fun clientBuilder(
        timeout: Long
    ): OkHttpClient.Builder {
        return baseClientBuilder()
            // timeouts
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            // interceptors
            .addInterceptor(HeadersInterceptor())
            .addInterceptor(HttpLoggingInterceptor())
    }
}

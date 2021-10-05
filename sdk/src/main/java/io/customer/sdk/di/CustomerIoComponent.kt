package io.customer.sdk.di

import io.customer.sdk.CustomerIoClient
import io.customer.sdk.CustomerIoConfig
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.api.retrofit.CustomerIoCallAdapterFactory
import io.customer.sdk.repository.IdentityRepositoryImpl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * CustomerIoComponent is the configuration class to configure/initialize low-level operations and objects.
 */
internal class CustomerIoComponent(
    private val customerIoConfig: CustomerIoConfig
) {

    fun buildApi(): CustomerIoApi {
        return CustomerIoClient(
            identityRepository = IdentityRepositoryImpl(
                customerService = buildRetrofitApi()
            )
        )
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return buildRetrofit(
            customerIoConfig.region.baseUrl,
            customerIoConfig.timeout,
        ).create(apiClass)
    }

    private fun buildRetrofit(
        endpoint: String,
        timeout: Long
    ): Retrofit {
        val okHttpClient = clientBuilder(timeout).build()
        return Retrofit.Builder()
            .baseUrl(endpoint)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .addCallAdapterFactory(CustomerIoCallAdapterFactory.create())
            .build()
    }

    private val baseClient: OkHttpClient by lazy { OkHttpClient() }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        baseClient.newBuilder()

    private fun clientBuilder(
        timeout: Long,
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

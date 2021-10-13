package io.customer.sdk.di

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.customer.sdk.BuildConfig
import io.customer.sdk.CustomerIOClient
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.api.retrofit.CustomerIoCallAdapterFactory
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.data.moshi.adapter.SupportedAttributesFactory
import io.customer.sdk.repository.IdentityRepositoryImpl
import io.customer.sdk.repository.MoshiAttributesRepositoryImp
import io.customer.sdk.repository.PreferenceRepositoryImpl
import io.customer.sdk.repository.TrackingRepositoryImp
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
internal class CustomerIOComponent(
    private val customerIOConfig: CustomerIOConfig,
    private val context: Context
) {

    fun buildApi(): CustomerIoApi {
        return CustomerIOClient(
            identityRepository = IdentityRepositoryImpl(
                customerService = buildRetrofitApi<CustomerService>(),
                attributesRepository = attributesRepository
            ),
            preferenceRepository = sharedPreferenceRepository,
            trackingRepository = TrackingRepositoryImp(
                customerService = buildRetrofitApi<CustomerService>(),
                attributesRepository = attributesRepository
            )
        )
    }

    private val sharedPreferenceRepository by lazy {
        PreferenceRepositoryImpl(
            context = context
        )
    }

    private val attributesRepository by lazy {
        MoshiAttributesRepositoryImp(
            jsonAdapter = jsonAdapter
        )
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return buildRetrofit(
            customerIOConfig.region.baseUrl,
            customerIOConfig.timeout,
        ).create(apiClass)
    }

    private val moshi by lazy {
        Moshi.Builder()
            .add(SupportedAttributesFactory())
            .build()
    }

    private val jsonAdapter: JsonAdapter<Map<String, Any>> by lazy {
        moshi.adapter(
            Types.newParameterizedType(
                MutableMap::class.java,
                String::class.java,
                Any::class.java
            )
        )
    }

    private val httpLoggingInterceptor by lazy {
        HttpLoggingInterceptor().apply {
            if (BuildConfig.DEBUG) {
                level = HttpLoggingInterceptor.Level.BODY
            }
        }
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
            .addInterceptor(httpLoggingInterceptor)
    }
}

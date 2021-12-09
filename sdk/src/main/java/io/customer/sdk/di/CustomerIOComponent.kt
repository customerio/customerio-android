package io.customer.sdk.di

import android.content.Context
import io.customer.sdk.BuildConfig
import io.customer.sdk.CustomerIOClient
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.Version
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.api.retrofit.CustomerIoCallAdapterFactory
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.api.service.PushService
import io.customer.sdk.data.moshi.CustomerIOParser
import io.customer.sdk.data.moshi.CustomerIOParserImpl
import io.customer.sdk.data.store.*
import io.customer.sdk.repository.*
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

    fun buildApi(): CustomerIOApi {
        return CustomerIOClient(
            identityRepository = IdentityRepositoryImpl(
                customerService = buildRetrofitApi<CustomerService>(),
                attributesRepository = attributesRepository
            ),
            preferenceRepository = sharedPreferenceRepository,
            trackingRepository = TrackingRepositoryImp(
                customerService = buildRetrofitApi<CustomerService>(),
                attributesRepository = attributesRepository
            ),
            pushNotificationRepository = PushNotificationRepositoryImp(
                customerService = buildRetrofitApi<CustomerService>(),
                pushService = buildRetrofitApi<PushService>()
            )
        )
    }

    fun buildStore(): CustomerIOStore {
        return object : CustomerIOStore {
            override val deviceStore: DeviceStore by lazy {
                DeviceStoreImp(
                    BuildStoreImp(),
                    ApplicationStoreImp(context),
                    Version.version
                )
            }
        }
    }

    private val sharedPreferenceRepository by lazy {
        PreferenceRepositoryImpl(
            context = context
        )
    }

    private val attributesRepository by lazy {
        MoshiAttributesRepositoryImp(
            parser = customerIOParser
        )
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return buildRetrofit(
            customerIOConfig.region.baseUrl,
            customerIOConfig.timeout,
        ).create(apiClass)
    }

    private val customerIOParser: CustomerIOParser by lazy { CustomerIOParserImpl() }

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

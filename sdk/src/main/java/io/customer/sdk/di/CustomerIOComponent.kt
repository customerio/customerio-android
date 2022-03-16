package io.customer.sdk.di

import android.content.Context
import com.squareup.moshi.Moshi
import io.customer.sdk.BuildConfig
import io.customer.sdk.CustomerIOClient
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.Version
import io.customer.sdk.api.CustomerIOAPIHttpClient
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.api.RetrofitCustomerIOAPIHttpClient
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.api.retrofit.CustomerIoCallAdapterFactory
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.api.service.PushService
import io.customer.sdk.data.moshi.adapter.BigDecimalAdapter
import io.customer.sdk.data.moshi.adapter.CustomAttributesFactory
import io.customer.sdk.data.moshi.adapter.UnixDateAdapter
import io.customer.sdk.data.store.*
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.QueueImpl
import io.customer.sdk.queue.QueueRunRequest
import io.customer.sdk.queue.QueueRunRequestImpl
import io.customer.sdk.queue.QueueStorage
import io.customer.sdk.queue.QueueStorageImpl
import io.customer.sdk.queue.type.QueueRunner
import io.customer.sdk.queue.type.QueueRunnerImpl
import io.customer.sdk.repository.*
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.DateUtilImpl
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.LogcatLogger
import io.customer.sdk.util.Logger
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
class CustomerIOComponent : DiGraph() {

    lateinit var context: Context
    lateinit var sdkConfig: CustomerIOConfig

    val fileStorage: FileStorage
        get() = override() ?: FileStorage(sdkConfig, context)

    val jsonAdapter: JsonAdapter
        get() = override() ?: JsonAdapter(moshi)

    val queueStorage: QueueStorage
        get() = override() ?: QueueStorageImpl(sdkConfig, fileStorage, jsonAdapter)

    val queueRunner: QueueRunner
        get() = override() ?: QueueRunnerImpl(jsonAdapter, cioHttpClient)

    val queue: Queue
        get() = override() ?: QueueImpl(queueStorage, queueRunRequest, jsonAdapter, sdkConfig, logger)

    val queueRunRequest: QueueRunRequest by lazy {
        override() ?: QueueRunRequestImpl(queueRunner, queueStorage, logger)
    }

    val logger: Logger
        get() = override() ?: LogcatLogger()

    internal val cioHttpClient: CustomerIOAPIHttpClient
        get() = override() ?: RetrofitCustomerIOAPIHttpClient(buildRetrofitApi())

    val dateUtil: DateUtil
        get() = override() ?: DateUtilImpl()

    internal fun buildApi(): CustomerIOApi {
        return override() ?: CustomerIOClient(
            identityRepository = IdentityRepositoryImpl(
                customerIOService = buildRetrofitApi<CustomerIOService>()
            ),
            preferenceRepository = sharedPreferenceRepository,
            pushNotificationRepository = PushNotificationRepositoryImp(
                customerIOService = buildRetrofitApi<CustomerIOService>(),
                pushService = buildRetrofitApi<PushService>()
            ),
            backgroundQueue = queue,
            dateUtil = dateUtil,
            logger = logger
        )
    }

    fun buildStore(): CustomerIOStore {
        return override() ?: object : CustomerIOStore {
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
        override() ?: PreferenceRepositoryImpl(context, sdkConfig)
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return override() ?: buildRetrofit(
            sdkConfig.region.baseUrl,
            sdkConfig.timeout,
        ).create(apiClass)
    }

    private val httpLoggingInterceptor by lazy {
        override() ?: HttpLoggingInterceptor().apply {
            if (BuildConfig.DEBUG) {
                level = HttpLoggingInterceptor.Level.BODY
            }
        }
    }

    // performance improvement to keep created moshi instance for re-use.
    val moshi: Moshi by lazy {
        override() ?: Moshi.Builder()
            .add(UnixDateAdapter())
            .add(BigDecimalAdapter())
            .add(CustomAttributesFactory())
            .build()
    }

    private fun buildRetrofit(
        endpoint: String,
        timeout: Long
    ): Retrofit {
        val okHttpClient = clientBuilder(timeout).build()
        return override() ?: Retrofit.Builder()
            .baseUrl(endpoint)
            .client(okHttpClient)
            .addCallAdapterFactory(CustomerIoCallAdapterFactory.create())
            .build()
    }

    private val baseClient: OkHttpClient by lazy { override() ?: OkHttpClient() }

    private fun baseClientBuilder(): OkHttpClient.Builder = override() ?: baseClient.newBuilder()

    private fun clientBuilder(
        timeout: Long,
    ): OkHttpClient.Builder {
        return override() ?: baseClientBuilder()
            // timeouts
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            // interceptors
            .addInterceptor(HeadersInterceptor(buildStore(), sdkConfig))
            .addInterceptor(httpLoggingInterceptor)
    }
}

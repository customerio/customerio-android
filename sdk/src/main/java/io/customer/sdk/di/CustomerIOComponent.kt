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
import io.customer.sdk.queue.QueueRequestManager
import io.customer.sdk.queue.QueueRequestManagerImpl
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
internal class CustomerIOComponent(
    private val customerIOConfig: CustomerIOConfig,
    private val context: Context
) {

    val sdkConfig: CustomerIOConfig
        get() = customerIOConfig

    val siteId: String
        get() = customerIOConfig.siteId

    val fileStorage: FileStorage
        get() = FileStorage(siteId, context)

    val jsonAdapter: JsonAdapter
        get() = JsonAdapter(moshi)

    val queueStorage: QueueStorage
        get() = QueueStorageImpl(siteId, fileStorage, jsonAdapter)

    val queueRunner: QueueRunner
        get() = QueueRunnerImpl(jsonAdapter, cioHttpClient)

    val queueRunRequestManager: QueueRequestManager by lazy { QueueRequestManagerImpl() }

    val queueRunRequest: QueueRunRequest
        get() = QueueRunRequestImpl(queueRunner, queueStorage, logger, queueRunRequestManager)

    val queue: Queue
        get() = QueueImpl(queueStorage, queueRunRequest, jsonAdapter, sdkConfig, logger)

    val logger: Logger
        get() = LogcatLogger()

    val cioHttpClient: CustomerIOAPIHttpClient
        get() = RetrofitCustomerIOAPIHttpClient(buildRetrofitApi())

    val dateUtil: DateUtil
        get() = DateUtilImpl()

    fun buildApi(): CustomerIOApi {
        return CustomerIOClient(
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
            context = context,
            siteId = siteId
        )
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return buildRetrofit(
            customerIOConfig.region.baseUrl,
            customerIOConfig.timeout,
        ).create(apiClass)
    }

    private val httpLoggingInterceptor by lazy {
        HttpLoggingInterceptor().apply {
            if (BuildConfig.DEBUG) {
                level = HttpLoggingInterceptor.Level.BODY
            }
        }
    }

    // performance improvement to keep created moshi instance for re-use.
    val moshi: Moshi by lazy {
        Moshi.Builder()
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
        return Retrofit.Builder()
            .baseUrl(endpoint)
            .client(okHttpClient)
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

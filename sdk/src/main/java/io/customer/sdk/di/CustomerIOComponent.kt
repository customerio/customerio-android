package io.customer.sdk.di

import android.content.Context
import com.squareup.moshi.Moshi
import io.customer.sdk.CustomerIOActivityLifecycleCallbacks
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.Version
import io.customer.sdk.api.*
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.data.moshi.adapter.BigDecimalAdapter
import io.customer.sdk.data.moshi.adapter.CustomAttributesFactory
import io.customer.sdk.data.moshi.adapter.UnixDateAdapter
import io.customer.sdk.data.store.*
import io.customer.sdk.hooks.CioHooksManager
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.queue.*
import io.customer.sdk.repository.*
import io.customer.sdk.util.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
class CustomerIOComponent(
    private val sharedComponent: CustomerIOSharedComponent,
    val context: Context,
    val sdkConfig: CustomerIOConfig
) : DiGraph() {

    val fileStorage: FileStorage
        get() = override() ?: FileStorage(sdkConfig, context, logger)

    val jsonAdapter: JsonAdapter
        get() = override() ?: JsonAdapter(moshi)

    val queueStorage: QueueStorage
        get() = override() ?: QueueStorageImpl(sdkConfig, fileStorage, jsonAdapter, dateUtil, logger)

    val queueRunner: QueueRunner
        get() = override() ?: QueueRunnerImpl(jsonAdapter, cioHttpClient, logger)

    val dispatchersProvider: DispatchersProvider
        get() = sharedComponent.dispatchersProvider

    val queue: Queue
        get() = override() ?: getSingletonInstanceCreate {
            QueueImpl(dispatchersProvider, queueStorage, queueRunRequest, jsonAdapter, sdkConfig, timer, logger, dateUtil)
        }

    internal val cleanupRepository: CleanupRepository
        get() = override() ?: CleanupRepositoryImpl(queue)

    val queueQueryRunner: QueueQueryRunner
        get() = override() ?: QueueQueryRunnerImpl(logger)

    val queueRunRequest: QueueRunRequest
        get() = override() ?: QueueRunRequestImpl(
            queueRunner,
            queueStorage,
            logger,
            queueQueryRunner
        )

    val logger: Logger
        get() = sharedComponent.logger

    val hooksManager: HooksManager
        get() = override() ?: getSingletonInstanceCreate { CioHooksManager() }

    internal val cioHttpClient: TrackingHttpClient
        get() = override() ?: RetrofitTrackingHttpClient(buildRetrofitApi(), httpRequestRunner)

    private val httpRequestRunner: HttpRequestRunner
        get() = HttpRequestRunnerImpl(
            sharedPreferenceRepository,
            logger,
            cioHttpRetryPolicy,
            jsonAdapter
        )

    val cioHttpRetryPolicy: HttpRetryPolicy
        get() = override() ?: CustomerIOApiRetryPolicy()

    val dateUtil: DateUtil
        get() = override() ?: DateUtilImpl()

    val timer: SimpleTimer
        get() = override() ?: AndroidSimpleTimer(logger, dispatchersProvider)

    val trackRepository: TrackRepository
        get() = override() ?: TrackRepositoryImpl(
            sharedPreferenceRepository,
            queue,
            logger,
            hooksManager
        )

    val profileRepository: ProfileRepository
        get() = override() ?: ProfileRepositoryImpl(
            deviceRepository,
            sharedPreferenceRepository,
            queue,
            logger,
            hooksManager
        )

    val deviceRepository: DeviceRepository
        get() = override() ?: DeviceRepositoryImpl(
            sdkConfig,
            buildStore().deviceStore,
            sharedPreferenceRepository,
            queue,
            dateUtil,
            logger
        )

    val activityLifecycleCallbacks: CustomerIOActivityLifecycleCallbacks
        get() = override() ?: getSingletonInstanceCreate {
            CustomerIOActivityLifecycleCallbacks(sdkConfig)
        }

    fun buildStore(): CustomerIOStore {
        return override() ?: object : CustomerIOStore {
            override val deviceStore: DeviceStore by lazy {
                DeviceStoreImp(
                    sdkConfig,
                    BuildStoreImp(),
                    ApplicationStoreImp(context),
                    Version.version
                )
            }
        }
    }

    val sharedPreferenceRepository: PreferenceRepository by lazy {
        override() ?: PreferenceRepositoryImpl(
            context = context,
            config = sdkConfig
        )
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return override() ?: buildRetrofit(
            sdkConfig.trackingApiHostname,
            sdkConfig.timeout
        ).create(apiClass)
    }

    private val httpLoggingInterceptor by lazy {
        override() ?: HttpLoggingInterceptor().apply {
            if (sharedComponent.staticSettingsProvider.isDebuggable) {
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

    fun buildRetrofit(
        endpoint: String,
        timeout: Long
    ): Retrofit {
        val okHttpClient = clientBuilder(timeout).build()
        return override() ?: Retrofit.Builder()
            .baseUrl(endpoint)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
    }

    private val baseClient: OkHttpClient by lazy { override() ?: OkHttpClient() }

    private fun baseClientBuilder(): OkHttpClient.Builder = override() ?: baseClient.newBuilder()

    private fun clientBuilder(
        timeout: Long
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

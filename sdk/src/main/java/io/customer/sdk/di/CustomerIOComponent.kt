package io.customer.sdk.di

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.customer.sdk.CustomerIOActivityLifecycleCallbacks
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.api.CustomerIOApiRetryPolicy
import io.customer.sdk.api.HttpRequestRunner
import io.customer.sdk.api.HttpRequestRunnerImpl
import io.customer.sdk.api.HttpRetryPolicy
import io.customer.sdk.api.RetrofitTrackingHttpClient
import io.customer.sdk.api.TrackingHttpClient
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.data.moshi.adapter.CustomAttributeContextualSerializer
import io.customer.sdk.data.store.ApplicationStoreImp
import io.customer.sdk.data.store.BuildStoreImp
import io.customer.sdk.data.store.CustomerIOStore
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceStoreImp
import io.customer.sdk.data.store.FileStorage
import io.customer.sdk.hooks.CioHooksManager
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.QueueImpl
import io.customer.sdk.queue.QueueQueryRunner
import io.customer.sdk.queue.QueueQueryRunnerImpl
import io.customer.sdk.queue.QueueRunRequest
import io.customer.sdk.queue.QueueRunRequestImpl
import io.customer.sdk.queue.QueueRunner
import io.customer.sdk.queue.QueueRunnerImpl
import io.customer.sdk.queue.QueueStorage
import io.customer.sdk.queue.QueueStorageImpl
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.repository.CleanupRepositoryImpl
import io.customer.sdk.repository.DeviceRepository
import io.customer.sdk.repository.DeviceRepositoryImpl
import io.customer.sdk.repository.ProfileRepository
import io.customer.sdk.repository.ProfileRepositoryImpl
import io.customer.sdk.repository.TrackRepository
import io.customer.sdk.repository.TrackRepositoryImpl
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.repository.preference.SitePreferenceRepositoryImpl
import io.customer.sdk.util.AndroidSimpleTimer
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.DateUtilImpl
import io.customer.sdk.util.DispatchersProvider
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.Logger
import io.customer.sdk.util.SimpleTimer
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
class CustomerIOComponent(
    private val staticComponent: CustomerIOStaticComponent,
    val context: Context,
    val sdkConfig: CustomerIOConfig
) : DiGraph() {

    val fileStorage: FileStorage
        get() = override() ?: FileStorage(config = sdkConfig, context = context, logger = logger)

    val jsonAdapter: JsonAdapter
        get() = override() ?: JsonAdapter(json)

    val queueStorage: QueueStorage
        get() = override() ?: QueueStorageImpl(
            sdkConfig = sdkConfig,
            fileStorage = fileStorage,
            jsonAdapter = jsonAdapter,
            dateUtil = dateUtil,
            logger = logger
        )

    val queueRunner: QueueRunner
        get() = override() ?: QueueRunnerImpl(
            jsonAdapter = jsonAdapter,
            cioHttpClient = cioHttpClient,
            logger = logger
        )

    val dispatchersProvider: DispatchersProvider
        get() = override() ?: staticComponent.dispatchersProvider

    val queue: Queue
        get() = override() ?: getSingletonInstanceCreate {
            QueueImpl(
                dispatchersProvider = dispatchersProvider,
                storage = queueStorage,
                runRequest = queueRunRequest,
                jsonAdapter = jsonAdapter,
                sdkConfig = sdkConfig,
                queueTimer = timer,
                logger = logger,
                dateUtil = dateUtil
            )
        }

    internal val cleanupRepository: CleanupRepository
        get() = override() ?: CleanupRepositoryImpl(queue = queue)

    val queueQueryRunner: QueueQueryRunner
        get() = override() ?: QueueQueryRunnerImpl(logger = logger)

    val queueRunRequest: QueueRunRequest
        get() = override() ?: QueueRunRequestImpl(
            runner = queueRunner,
            queueStorage = queueStorage,
            logger = logger,
            queryRunner = queueQueryRunner
        )

    val logger: Logger
        get() = override() ?: staticComponent.logger

    val hooksManager: HooksManager
        get() = override() ?: getSingletonInstanceCreate { CioHooksManager() }

    private val cioHttpClient: TrackingHttpClient
        get() = override() ?: RetrofitTrackingHttpClient(
            retrofitService = buildRetrofitApi(),
            httpRequestRunner = httpRequestRunner
        )

    private val httpRequestRunner: HttpRequestRunner
        get() = HttpRequestRunnerImpl(
            prefsRepository = sitePreferenceRepository,
            logger = logger,
            retryPolicy = cioHttpRetryPolicy,
            jsonAdapter = jsonAdapter
        )

    val cioHttpRetryPolicy: HttpRetryPolicy
        get() = override() ?: CustomerIOApiRetryPolicy()

    val dateUtil: DateUtil
        get() = override() ?: DateUtilImpl()

    val timer: SimpleTimer
        get() = override() ?: AndroidSimpleTimer(
            logger = logger,
            dispatchersProvider = dispatchersProvider
        )

    val trackRepository: TrackRepository
        get() = override() ?: TrackRepositoryImpl(
            sitePreferenceRepository = sitePreferenceRepository,
            backgroundQueue = queue,
            logger = logger,
            hooksManager = hooksManager
        )

    val profileRepository: ProfileRepository
        get() = override() ?: ProfileRepositoryImpl(
            deviceRepository = deviceRepository,
            sitePreferenceRepository = sitePreferenceRepository,
            backgroundQueue = queue,
            logger = logger,
            hooksManager = hooksManager
        )

    val deviceRepository: DeviceRepository
        get() = override() ?: DeviceRepositoryImpl(
            config = sdkConfig,
            deviceStore = buildStore().deviceStore,
            sitePreferenceRepository = sitePreferenceRepository,
            backgroundQueue = queue,
            dateUtil = dateUtil,
            logger = logger
        )

    val activityLifecycleCallbacks: CustomerIOActivityLifecycleCallbacks
        get() = override() ?: getSingletonInstanceCreate {
            CustomerIOActivityLifecycleCallbacks(config = sdkConfig)
        }

    private fun buildStore(): CustomerIOStore {
        return override() ?: object : CustomerIOStore {
            override val deviceStore: DeviceStore by lazy {
                DeviceStoreImp(
                    sdkConfig = sdkConfig,
                    buildStore = BuildStoreImp(),
                    applicationStore = ApplicationStoreImp(context),
                    version = sdkConfig.client.sdkVersion
                )
            }
        }
    }

    val sitePreferenceRepository: SitePreferenceRepository by lazy {
        override() ?: SitePreferenceRepositoryImpl(
            context = context,
            config = sdkConfig
        )
    }

    private inline fun <reified T> buildRetrofitApi(): T {
        val apiClass = T::class.java
        return override() ?: buildRetrofit(
            endpoint = sdkConfig.trackingApiHostname,
            timeout = sdkConfig.timeout
        ).create(apiClass)
    }

    private val httpLoggingInterceptor by lazy {
        override() ?: HttpLoggingInterceptor().apply {
            if (staticComponent.staticSettingsProvider.isDebuggable) {
                level = HttpLoggingInterceptor.Level.BODY
            }
        }
    }

    val module = SerializersModule {
        contextual(CustomAttributeContextualSerializer(logger = logger))
    }

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            serializersModule = module
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildRetrofit(
        endpoint: String,
        timeout: Long
    ): Retrofit {
        val okHttpClient = clientBuilder(timeout).build()
        val contentType = "application/json".toMediaType()
        return override() ?: Retrofit.Builder().baseUrl(endpoint)
            .addConverterFactory(json.asConverterFactory(contentType)).client(okHttpClient).build()
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

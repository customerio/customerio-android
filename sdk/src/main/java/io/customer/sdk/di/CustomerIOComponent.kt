package io.customer.sdk.di

import android.content.Context
import com.squareup.moshi.Moshi
import io.customer.sdk.BuildConfig
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.Version
import io.customer.sdk.api.CustomerIOApiRetryPolicy
import io.customer.sdk.api.HttpRequestRunner
import io.customer.sdk.api.HttpRequestRunnerImpl
import io.customer.sdk.api.HttpRetryPolicy
import io.customer.sdk.api.RetrofitTrackingHttpClient
import io.customer.sdk.api.TrackingHttpClient
import io.customer.sdk.api.interceptors.HeadersInterceptor
import io.customer.sdk.data.moshi.adapter.BigDecimalAdapter
import io.customer.sdk.data.moshi.adapter.CustomAttributesFactory
import io.customer.sdk.data.moshi.adapter.UnixDateAdapter
import io.customer.sdk.data.store.*
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
import io.customer.sdk.repository.*
import io.customer.sdk.util.AndroidSimpleTimer
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.DateUtilImpl
import io.customer.sdk.util.DispatchersProvider
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.LogcatLogger
import io.customer.sdk.util.Logger
import io.customer.sdk.util.PushTrackingUtil
import io.customer.sdk.util.PushTrackingUtilImpl
import io.customer.sdk.util.SdkDispatchers
import io.customer.sdk.util.SimpleTimer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
class CustomerIOComponent(
    val context: Context,
    val sdkConfig: CustomerIOConfig
) : DiGraph() {

    val pushTrackingUtil: PushTrackingUtil
        get() = override() ?: PushTrackingUtilImpl(trackRepository)

    val fileStorage: FileStorage
        get() = override() ?: FileStorage(sdkConfig, context, logger)

    val jsonAdapter: JsonAdapter
        get() = override() ?: JsonAdapter(moshi)

    val queueStorage: QueueStorage
        get() = override() ?: QueueStorageImpl(sdkConfig, fileStorage, jsonAdapter, dateUtil, logger)

    val queueRunner: QueueRunner
        get() = override() ?: QueueRunnerImpl(jsonAdapter, cioHttpClient, logger)

    val dispatchersProvider: DispatchersProvider
        get() = override() ?: SdkDispatchers()

    val queue: Queue
        get() = override() ?: getSingletonInstanceCreate {
            QueueImpl(dispatchersProvider, queueStorage, queueRunRequest, jsonAdapter, sdkConfig, timer, logger, dateUtil)
        }

    internal val cleanupRepository: CleanupRepository
        get() = override() ?: CleanupRepositoryImpl(queue)

    val queueQueryRunner: QueueQueryRunner
        get() = override() ?: QueueQueryRunnerImpl(logger)

    val queueRunRequest: QueueRunRequest
        get() = override() ?: QueueRunRequestImpl(queueRunner, queueStorage, logger, queueQueryRunner)

    val logger: Logger
        get() = override() ?: LogcatLogger(sdkConfig)

    internal val cioHttpClient: TrackingHttpClient
        get() = override() ?: RetrofitTrackingHttpClient(buildRetrofitApi(), httpRequestRunner)

    private val httpRequestRunner: HttpRequestRunner
        get() = HttpRequestRunnerImpl(sharedPreferenceRepository, logger, cioHttpRetryPolicy, jsonAdapter)

    val cioHttpRetryPolicy: HttpRetryPolicy
        get() = override() ?: CustomerIOApiRetryPolicy()

    val dateUtil: DateUtil
        get() = override() ?: DateUtilImpl()

    val timer: SimpleTimer
        get() = override() ?: AndroidSimpleTimer(logger, dispatchersProvider)

    val trackRepository: TrackRepository
        get() = override() ?: TrackRepositoryImpl(sharedPreferenceRepository, queue, logger)

    val profileRepository: ProfileRepository
        get() = override() ?: ProfileRepositoryImpl(deviceRepository, sharedPreferenceRepository, queue, logger)

    val deviceRepository: DeviceRepository
        get() = override() ?: DeviceRepositoryImpl(sdkConfig, buildStore().deviceStore, sharedPreferenceRepository, queue, dateUtil, logger)

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

package io.customer.sdk

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.data.store.Client
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.extensions.getScreenNameFromActivity
import io.customer.sdk.module.CustomerIOModule
import io.customer.sdk.module.CustomerIOModuleConfig
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.repository.DeviceRepository
import io.customer.sdk.repository.ProfileRepository
import io.customer.sdk.repository.TrackRepository
import io.customer.sdk.util.CioLogLevel
import io.customer.sdk.util.Seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Allows mocking of [CustomerIO] for your automated tests in your project. Mock [CustomerIO] to assert your code is calling functions
 * of the SDK and/or do not have the SDK run it's real implementation during automated tests.
 */
interface CustomerIOInstance {
    val siteId: String
    val sdkVersion: String
    // For security reasons, do not expose the SDK config as anyone can get the API key from the SDK including 3rd parties.

    var profileAttributes: CustomAttributes
    var deviceAttributes: CustomAttributes

    fun identify(identifier: String)

    fun identify(
        identifier: String,
        attributes: Map<String, Any>
    )

    fun track(name: String)

    fun track(
        name: String,
        attributes: Map<String, Any>
    )

    fun screen(name: String)

    fun screen(
        name: String,
        attributes: Map<String, Any>
    )

    fun screen(activity: Activity)

    fun screen(
        activity: Activity,
        attributes: Map<String, Any>
    )

    fun clearIdentify()

    fun registerDeviceToken(deviceToken: String)

    fun deleteDeviceToken()

    fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    )
}

/**
Welcome to the Customer.io Android SDK!
This class is where you begin to use the SDK.
You must have an instance of `CustomerIO` to use the features of the SDK.
Create your own instance using
`CustomerIo.Builder(siteId: "XXX", apiKey: "XXX", region: Region.US, appContext: Application context)`
It is recommended to initialize the client in the `Application::onCreate()` method.
After the instance is created you can access it via singleton instance: `CustomerIO.instance()` anywhere,
 */
class CustomerIO internal constructor(
    /**
     * Strong reference to graph that other top-level classes in SDK can use `CustomerIO.instance().diGraph`.
     */
    val diGraph: CustomerIOComponent
) : CustomerIOInstance {

    companion object {
        private var instance: CustomerIO? = null

        fun instanceOrNull(): CustomerIO? = try {
            instance()
        } catch (ex: Exception) {
            null
        }

        @JvmStatic
        fun instance(): CustomerIO {
            return instance
                ?: throw IllegalStateException("CustomerIO.Builder::build() must be called before obtaining CustomerIO instance")
        }
    }

    class Builder @JvmOverloads constructor(
        private val siteId: String,
        private val apiKey: String,
        private var region: Region = Region.US,
        private val appContext: Application
    ) {
        private var client: Client = Client.android(Version.version)
        private var timeout = 6000L
        private var shouldAutoRecordScreenViews: Boolean = false
        private var autoTrackDeviceAttributes: Boolean = true
        private val modules: MutableMap<String, CustomerIOModule<out CustomerIOModuleConfig>> = mutableMapOf()
        private var logLevel = CioLogLevel.ERROR
        internal var overrideDiGraph: CustomerIOComponent? = null // set for automated tests
        private var trackingApiUrl: String? = null
        private var backgroundQueueMinNumberOfTasks: Int = 10
        private var backgroundQueueSecondsDelay: Double = 30.0

        fun setClient(client: Client): Builder {
            this.client = client
            return this
        }

        fun setRegion(region: Region): Builder {
            this.region = region
            return this
        }

        fun setRequestTimeout(timeout: Long): Builder {
            this.timeout = timeout
            return this
        }

        fun autoTrackScreenViews(shouldRecordScreenViews: Boolean): Builder {
            this.shouldAutoRecordScreenViews = shouldRecordScreenViews
            return this
        }

        fun autoTrackDeviceAttributes(shouldTrackDeviceAttributes: Boolean): Builder {
            this.autoTrackDeviceAttributes = shouldTrackDeviceAttributes
            return this
        }

        fun setLogLevel(level: CioLogLevel): Builder {
            this.logLevel = level
            return this
        }

        /**
         * Base URL to use for the Customer.io track API. You will more then likely not modify this value.
         * If you override this value, `Region` set when initializing the SDK will be ignored.
         */
        fun setTrackingApiURL(trackingApiUrl: String): Builder {
            this.trackingApiUrl = trackingApiUrl
            return this
        }

        /**
         * Sets the number of tasks in the background queue before the queue begins operating.
         * This is mostly used during development to test configuration is setup. We do not recommend
         * modifying this value because it impacts battery life of mobile device.
         *
         * @param backgroundQueueMinNumberOfTasks the minimum number of tasks in background queue; default 10
         */
        fun setBackgroundQueueMinNumberOfTasks(backgroundQueueMinNumberOfTasks: Int): Builder {
            this.backgroundQueueMinNumberOfTasks = backgroundQueueMinNumberOfTasks
            return this
        }

        /**
         * Sets the number of seconds to delay running queue after a task has been added to it
         *
         * @param backgroundQueueSecondsDelay time in seconds to delay events; default 30
         */
        fun setBackgroundQueueSecondsDelay(backgroundQueueSecondsDelay: Double): Builder {
            this.backgroundQueueSecondsDelay = backgroundQueueSecondsDelay
            return this
        }

        fun <Config : CustomerIOModuleConfig> addCustomerIOModule(module: CustomerIOModule<Config>): Builder {
            modules[module.moduleName] = module
            return this
        }

        fun build(): CustomerIO {
            if (apiKey.isEmpty()) {
                throw IllegalStateException("apiKey is not defined in " + this::class.java.simpleName)
            }

            if (siteId.isEmpty()) {
                throw IllegalStateException("siteId is not defined in " + this::class.java.simpleName)
            }

            val config = CustomerIOConfig(
                client = client,
                siteId = siteId,
                apiKey = apiKey,
                region = region,
                timeout = timeout,
                autoTrackScreenViews = shouldAutoRecordScreenViews,
                autoTrackDeviceAttributes = autoTrackDeviceAttributes,
                backgroundQueueMinNumberOfTasks = backgroundQueueMinNumberOfTasks,
                backgroundQueueSecondsDelay = backgroundQueueSecondsDelay,
                backgroundQueueTaskExpiredSeconds = Seconds.fromDays(3).value,
                logLevel = logLevel,
                trackingApiUrl = trackingApiUrl,
                targetSdkVersion = appContext.applicationInfo.targetSdkVersion,
                configurations = modules.entries.associate { entry -> entry.key to entry.value.moduleConfig }
            )

            val diGraph = overrideDiGraph ?: CustomerIOComponent(sdkConfig = config, context = appContext)
            val client = CustomerIO(diGraph)
            val logger = diGraph.logger

            instance = client

            appContext.registerActivityLifecycleCallbacks(diGraph.activityLifecycleCallbacks)
            modules.forEach {
                logger.debug("initializing SDK module ${it.value.moduleName}...")
                it.value.initialize()
            }

            client.postInitialize()

            return client
        }
    }

    private val trackRepository: TrackRepository
        get() = diGraph.trackRepository

    private val deviceRepository: DeviceRepository
        get() = diGraph.deviceRepository

    private val profileRepository: ProfileRepository
        get() = diGraph.profileRepository

    override val siteId: String
        get() = diGraph.sdkConfig.siteId

    override val sdkVersion: String
        get() = Version.version

    private val cleanupRepository: CleanupRepository
        get() = diGraph.cleanupRepository

    private fun postInitialize() {
        // run cleanup asynchronously in background to prevent taking up the main/UI thread
        CoroutineScope(diGraph.dispatchersProvider.background).launch {
            cleanupRepository.cleanup()
        }
    }

    /**
     * Identify a customer (aka: Add or update a profile).
     * [Learn more](https://customer.io/docs/identifying-people/) about identifying a customer in Customer.io
     * Note: You can only identify 1 profile at a time in your SDK. If you call this function multiple times,
     * the previously identified profile will be removed. Only the latest identified customer is persisted.
     * @param identifier ID you want to assign to the customer.
     * This value can be an internal ID that your system uses or an email address.
     * [Learn more](https://customer.io/docs/api/#operation/identify)
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun identify(identifier: String) = this.identify(identifier, emptyMap())

    /**
     * Identify a customer (aka: Add or update a profile).
     * [Learn more](https://customer.io/docs/identifying-people/) about identifying a customer in Customer.io
     * Note: You can only identify 1 profile at a time in your SDK. If you call this function multiple times,
     * the previously identified profile will be removed. Only the latest identified customer is persisted.
     * @param identifier ID you want to assign to the customer.
     * This value can be an internal ID that your system uses or an email address.
     * [Learn more](https://customer.io/docs/api/#operation/identify)
     * @param attributes Map of <String, IdentityAttributeValue> to be added
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun identify(
        identifier: String,
        attributes: CustomAttributes
    ) = profileRepository.identify(identifier, attributes)

    /**
     * Track an event
     * [Learn more](https://customer.io/docs/events/) about events in Customer.io
     * @param name Name of the event you want to track.
     */
    override fun track(name: String) = this.track(name, emptyMap())

    /**
     * Track an event
     * [Learn more](https://customer.io/docs/events/) about events in Customer.io
     * @param name Name of the event you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun track(
        name: String,
        attributes: CustomAttributes
    ) = trackRepository.track(name, attributes)

    /**
     * Track screen
     * @param name Name of the screen you want to track.
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun screen(name: String) = this.screen(name, emptyMap())

    /**
     * Track screen
     * @param name Name of the screen you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun screen(
        name: String,
        attributes: CustomAttributes
    ) = trackRepository.screen(name, attributes)

    /**
     * Track activity screen, `label` added for this activity in `manifest` will be utilized for tracking
     * @param activity Instance of the activity you want to track.
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun screen(activity: Activity) = this.screen(activity, emptyMap())

    /**
     * Track activity screen, `label` added for this activity in `manifest` will be utilized for tracking
     * @param activity Instance of the activity you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun screen(
        activity: Activity,
        attributes: CustomAttributes
    ) = recordScreenViews(activity, attributes)

    /**
     * Stop identifying the currently persisted customer. All future calls to the SDK will no longer
     * be associated with the previously identified customer.
     * Note: If you simply want to identify a *new* customer, this function call is optional. Simply
     * call `identify()` again to identify the new customer profile over the existing.
     * If no profile has been identified yet, this function will ignore your request.
     */
    override fun clearIdentify() {
        profileRepository.clearIdentify()
    }

    /**
     * Register a new device token with Customer.io, associated with the current active customer. If there
     * is no active customer, this will fail to register the device
     */
    override fun registerDeviceToken(deviceToken: String) = deviceRepository.registerDeviceToken(deviceToken, deviceAttributes)

    /**
     * Delete the currently registered device token
     */
    override fun deleteDeviceToken() = deviceRepository.deleteDeviceToken()

    /**
     * Track a push metric
     */
    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    ) = trackRepository.trackMetric(
        deliveryID = deliveryID,
        event = event,
        deviceToken = deviceToken
    )

    /**
     * Use to provide attributes to the currently identified profile.
     *
     * Note: If there is not a profile identified, this request will be ignored.
     */
    override var profileAttributes: CustomAttributes = emptyMap()
        set(value) {
            profileRepository.addCustomProfileAttributes(value)
        }

    /**
     * Use to provide additional and custom device attributes
     * apart from the ones the SDK is programmed to send to customer workspace.
     */
    override var deviceAttributes: CustomAttributes = emptyMap()
        set(value) {
            field = value

            deviceRepository.addCustomDeviceAttributes(value)
        }

    private fun recordScreenViews(activity: Activity, attributes: CustomAttributes) {
        val packageManager = activity.packageManager
        return try {
            val info = packageManager.getActivityInfo(
                activity.componentName,
                PackageManager.GET_META_DATA
            )
            val activityLabel = info.loadLabel(packageManager)

            val screenName = activityLabel.toString().ifEmpty {
                activity::class.java.simpleName.getScreenNameFromActivity()
            }
            screen(screenName, attributes)
        } catch (e: PackageManager.NameNotFoundException) {
            // if `PackageManager.NameNotFoundException` is thrown, is that a bug in the SDK or a problem with the customer's app?
            // We may want to decide to log this as an SDK error, log it so customer notices it to fix it themselves, or we do nothing because this exception might not be a big issue.
            // ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(message = "Activity Not Found: $e")))
        } catch (e: Exception) {
            // Should we log exceptions that happen? Ignore them? How rare is an exception happen in this function?
            // ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(message = "Unable to track, $activity")))
        }
    }
}

package io.customer.sdk

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import io.customer.base.comunication.Action
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent

interface CustomerIOInstance {
    val siteId: String

    fun identify(identifier: String): Action<Unit>

    fun identify(
        identifier: String,
        attributes: Map<String, Any>
    ): Action<Unit>

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

    fun registerDeviceToken(deviceToken: String): Action<Unit>

    fun deleteDeviceToken(): Action<Unit>

    fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String,
    ): Action<Unit>
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
class CustomerIO constructor(
    override val siteId: String
) : CustomerIOInstance {
    companion object {
        private var instance: CustomerIO? = null

        @JvmStatic
        fun instance(): CustomerIO {
            return instance
                ?: throw IllegalStateException("CustomerIO.Builder::build() must be called before obtaining CustomerIO instance")
        }
    }

    class Builder(
        private val siteId: String,
        private val apiKey: String,
        private var region: Region = Region.US,
        private val appContext: Application
    ) {
        private var timeout = 6000L
        private var urlHandler: CustomerIOUrlHandler? = null
        private var shouldAutoRecordScreenViews: Boolean = false

        private lateinit var activityLifecycleCallback: CustomerIOActivityLifecycleCallbacks

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

        /**
         * Override url/deep link handling
         *
         * @param urlHandler callback called when deeplink push action is performed.
         */
        fun setCustomerIOUrlHandler(urlHandler: CustomerIOUrlHandler): Builder {
            this.urlHandler = urlHandler
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
                siteId = siteId,
                apiKey = apiKey,
                region = region,
                timeout = timeout,
                urlHandler = urlHandler,
                autoTrackScreenViews = shouldAutoRecordScreenViews,
                backgroundQueueMinNumberOfTasks = 10
            )

            CustomerIOComponent.createAndUpdate(siteId, appContext, config)

            val client = CustomerIO(siteId)

            activityLifecycleCallback = CustomerIOActivityLifecycleCallbacks(client, config)
            appContext.registerActivityLifecycleCallbacks(activityLifecycleCallback)

            instance = client
            return client
        }
    }

    // Since this class is at the top-most level of the MessagingPush module,
    // we get instances from the DiGraph, not through constructor dependency injection.
    private val diGraph: CustomerIOComponent
        get() = CustomerIOComponent.getInstance(siteId)

    private val api: CustomerIOApi
        get() = diGraph.buildApi()

    override fun identify(identifier: String): Action<Unit> = this.identify(identifier, emptyMap())

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
        attributes: Map<String, Any>
    ): Action<Unit> = api.identify(identifier, attributes)

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
        attributes: Map<String, Any>
    ) = api.track(name, attributes)

    override fun screen(name: String) = this.screen(name, emptyMap())

    /**
     * Track screen
     * @param name Name of the screen you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun screen(
        name: String,
        attributes: Map<String, Any>
    ) = api.screen(name, attributes)

    override fun screen(activity: Activity) = this.screen(activity, emptyMap())

    /**
     * Track activity screen, `label` added for this activity in `manifest` will be utilized for tracking
     * @param activity Instance of the activity you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    override fun screen(
        activity: Activity,
        attributes: Map<String, Any>
    ) = recordScreenViews(activity, attributes)

    /**
     * Stop identifying the currently persisted customer. All future calls to the SDK will no longer
     * be associated with the previously identified customer.
     * Note: If you simply want to identify a *new* customer, this function call is optional. Simply
     * call `identify()` again to identify the new customer profile over the existing.
     * If no profile has been identified yet, this function will ignore your request.
     */
    override fun clearIdentify() {
        api.clearIdentify()
    }

    /**
     * Register a new device token with Customer.io, associated with the current active customer. If there
     * is no active customer, this will fail to register the device
     */
    override fun registerDeviceToken(deviceToken: String): Action<Unit> =
        api.registerDeviceToken(deviceToken)

    /**
     * Delete the currently registered device token
     */
    override fun deleteDeviceToken(): Action<Unit> = api.deleteDeviceToken()

    /**
     * Track a push metric
     */
    override fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String,
    ) = api.trackMetric(
        deliveryID = deliveryID,
        event = event,
        deviceToken = deviceToken
    )

    private fun recordScreenViews(activity: Activity, attributes: Map<String, Any>) {
        val packageManager = activity.packageManager
        return try {
            val info = packageManager.getActivityInfo(
                activity.componentName, PackageManager.GET_META_DATA
            )
            val activityLabel = info.loadLabel(packageManager)
            screen(activityLabel.toString(), attributes)
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

package io.customer.sdk

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.utils.ActionUtils
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.data.store.CustomerIOStore
import io.customer.sdk.di.CustomerIOComponent

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
    val config: CustomerIOConfig,
    val store: CustomerIOStore,
    private val api: CustomerIOApi,
) {
    companion object {
        private var instance: CustomerIO? = null

        @JvmStatic
        fun instance(): CustomerIO {
            return instance
                ?: throw IllegalStateException("CustomerIo.Builder::build() must be called before obtaining CustomerIo instance")
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
                autoTrackScreenViews = shouldAutoRecordScreenViews
            )

            val customerIoComponent =
                CustomerIOComponent(customerIOConfig = config, context = appContext)

            val client = CustomerIO(
                config = config,
                store = customerIoComponent.buildStore(),
                api = customerIoComponent.buildApi(),
            )

            activityLifecycleCallback = CustomerIOActivityLifecycleCallbacks(client)
            appContext.registerActivityLifecycleCallbacks(activityLifecycleCallback)

            instance = client
            return client
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
     * @param attributes Map of <String, IdentityAttributeValue> to be added
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    fun identify(
        identifier: String,
        attributes: Map<String, Any> = emptyMap()
    ): Action<Unit> = api.identify(identifier, attributes)

    /**
     * Track an event
     * [Learn more](https://customer.io/docs/events/) about events in Customer.io
     * @param name Name of the event you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    fun track(
        name: String,
        attributes: Map<String, Any> = emptyMap()
    ) = api.track(name, attributes)

    /**
     * Track screen
     * @param name Name of the screen you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    fun screen(
        name: String,
        attributes: Map<String, Any> = emptyMap()
    ) = api.screen(name, attributes)

    /**
     * Track activity screen, `label` added for this activity in `manifest` will be utilized for tracking
     * @param activity Instance of the activity you want to track.
     * @param attributes Optional event body in Map format used as JSON object
     * @return Action<Unit> which can be accessed via `execute` or `enqueue`
     */
    fun screen(
        activity: Activity,
        attributes: Map<String, Any> = emptyMap()
    ) = recordScreenViews(activity, attributes)

    /**
     * Stop identifying the currently persisted customer. All future calls to the SDK will no longer
     * be associated with the previously identified customer.
     * Note: If you simply want to identify a *new* customer, this function call is optional. Simply
     * call `identify()` again to identify the new customer profile over the existing.
     * If no profile has been identified yet, this function will ignore your request.
     */
    fun clearIdentify() {
        api.clearIdentify()
    }

    /**
     * Register a new device token with Customer.io, associated with the current active customer. If there
     * is no active customer, this will fail to register the device
     */
    fun registerDeviceToken(deviceToken: String): Action<Unit> =
        api.registerDeviceToken(deviceToken, store.deviceStore.buildDeviceAttributes())

    /**
     * Delete the currently registered device token
     */
    fun deleteDeviceToken(): Action<Unit> = api.deleteDeviceToken()

    /**
     * Track a push metric
     */
    fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String,
    ) = api.trackMetric(
        deliveryID = deliveryID,
        event = event,
        deviceToken = deviceToken
    )

    private fun recordScreenViews(activity: Activity, attributes: Map<String, Any>): Action<Unit> {
        val packageManager = activity.packageManager
        return try {
            val info = packageManager.getActivityInfo(
                activity.componentName, PackageManager.GET_META_DATA
            )
            val activityLabel = info.loadLabel(packageManager)
            screen(activityLabel.toString(), attributes)
        } catch (e: PackageManager.NameNotFoundException) {
            ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(message = "Activity Not Found: $e")))
        } catch (e: Exception) {
            ActionUtils.getErrorAction(ErrorResult(error = ErrorDetail(message = "Unable to track, $activity")))
        }
    }
}

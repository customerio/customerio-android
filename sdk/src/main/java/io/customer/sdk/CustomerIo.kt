package io.customer.sdk

import android.content.Context
import io.customer.base.comunication.Action
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.data.model.Region
import io.customer.sdk.di.CustomerIoComponent

/**
Welcome to the Customer.io Android SDK!

This class is where you begin to use the SDK.
You must have an instance of `CustomerIO` to use the features of the SDK.

Create your own instance using
`CustomerIo.Builder(siteId: "XXX", apiKey: "XXX", region: Region.US, appContext: Application context)`
It is recommended to initialize the client in the `Application::onCreate()` method.

After the instance is created you can access it via singleton instance: `CustomerIO.instance()` anywhere,
 */

class CustomerIo internal constructor(
    val config: CustomerIoConfig,
    private val api: CustomerIoApi,
) {
    companion object {
        private var instance: CustomerIo? = null

        @JvmStatic
        fun instance(): CustomerIo {
            return instance
                ?: throw IllegalStateException("CustomerIo.Builder::build() must be called before obtaining CustomerIo instance")
        }
    }

    class Builder(
        private val siteId: String,
        private val apiKey: String,
        private var region: Region = Region.US,
        private val appContext: Context
    ) {
        private var timeout = 6000L

        fun setRegion(region: Region): Builder {
            this.region = region
            return this
        }

        fun setTimeout(timeout: Long): Builder {
            this.timeout = timeout
            return this
        }

        fun build(): CustomerIo {

            if (apiKey.isEmpty()) {
                throw IllegalStateException("apiKey is not defined in " + this::class.java.simpleName)
            }

            if (siteId.isEmpty()) {
                throw IllegalStateException("siteId is not defined in " + this::class.java.simpleName)
            }

            val config = CustomerIoConfig(
                siteId = siteId,
                apiKey = apiKey,
                region = region,
                timeout = timeout
            )

            val customerIoComponent =
                CustomerIoComponent(customerIoConfig = config, context = appContext)

            val client = CustomerIo(config, customerIoComponent.buildApi())
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
    ): Action<Unit> {
        return api.identify(identifier, attributes)
    }

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
    ): Action<Unit> {
        return api.track(name, attributes)
    }

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
}

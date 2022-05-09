package io.customer.sdk

/**
 * A module is optional Customer.io SDK that you can install in your app.
 *
 * This interface allows the base SDK to initialize all of the SDKs installed in an app and begin to communicate with them.
 */
interface CustomerIOModule {
    val moduleName: String
    fun initialize()
}

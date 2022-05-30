package io.customer.sdk

/**
 * A module is optional Customer.io SDK that you can install in your app.
 *
 * This interface allows the base SDK to initialize all of the SDKs installed in an app and begin to communicate with them.
 * It is recommended to keep the modules light as they are strongly referenced by *tracking* module
 */
interface CustomerIOModule {
    val moduleName: String
    fun initialize()
}

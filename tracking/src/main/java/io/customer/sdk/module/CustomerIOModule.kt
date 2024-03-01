package io.customer.sdk.module

/**
 * A module is optional Customer.io SDK that you can install in your app.
 *
 * This interface allows the base SDK to initialize all of the SDKs installed in an app and begin to communicate with them.
 * It is recommended to keep the modules light as they are strongly referenced by *tracking* module
 *
 * @param Config generic type of configurations required by the module
 */
interface CustomerIOModule<Config : CustomerIOModuleConfig> {
    val moduleName: String
    val moduleConfig: Config
    fun initialize()
}

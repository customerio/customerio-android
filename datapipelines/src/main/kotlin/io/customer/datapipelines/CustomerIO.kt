@file:Suppress("unused")

package io.customer.datapipelines

import android.app.Application
import io.customer.sdk.android.CustomerIO

/**
 * Creates a new instance of builder for CustomerIO SDK.
 * Builder class can be used for setting up SDK configuration and additional modules.
 * Using this method, data pipelines module is automatically included to ensure all
 * events are routed to it.
 *
 * Example usage:
 * ```
 * with(CustomerIO.Builder(appContext: Application context, cdpApiKey = "XXX")) {
 *   setLogLevel(...)
 *   addCustomerIOModule(...)
 *   build()
 * }
 * ```
 */
@Suppress("FunctionName")
fun CustomerIO.Companion.Builder(
    applicationContext: Application,
    cdpApiKey: String
): CustomerIOBuilder = CustomerIOBuilder(
    applicationContext = applicationContext,
    cdpApiKey = cdpApiKey
)

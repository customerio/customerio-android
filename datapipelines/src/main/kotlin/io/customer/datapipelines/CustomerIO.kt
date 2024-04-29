@file:Suppress("unused")

package io.customer.datapipelines

import android.app.Application
import io.customer.android.sdk.CustomerIO

@Suppress("FunctionName")
fun CustomerIO.Companion.Builder(
    applicationContext: Application,
    cdpApiKey: String
): CustomerIOBuilder = CustomerIOBuilder(
    applicationContext = applicationContext,
    cdpApiKey = cdpApiKey
)

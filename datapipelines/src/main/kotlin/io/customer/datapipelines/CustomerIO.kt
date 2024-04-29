package io.customer.datapipelines

import android.app.Application
import io.customer.sdk.CustomerIO

@Suppress("FunctionName")
fun CustomerIO.Companion.Builder(
    applicationContext: Application,
    cdpApiKey: String
): CustomerIOBuilder = CustomerIOBuilder(
    applicationContext = applicationContext,
    cdpApiKey = cdpApiKey
)

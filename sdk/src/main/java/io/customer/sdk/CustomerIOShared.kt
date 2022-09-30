package io.customer.sdk

import androidx.annotation.VisibleForTesting
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.util.LogcatLogger

class CustomerIOShared private constructor(
    val diGraph: CustomerIOSharedComponent
) {
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun attachSDKConfig(sdkConfig: CustomerIOConfig) {
        (diGraph.logger as? LogcatLogger)?.setPreferredLogLevel(logLevel = sdkConfig.logLevel)
    }

    companion object {
        private var INSTANCE: CustomerIOShared? = null

        @JvmStatic
        @OptIn(InternalCustomerIOApi::class)
        fun instance(): CustomerIOShared = createInstance(diGraph = null)

        @Synchronized
        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun createInstance(
            diGraph: CustomerIOSharedComponent? = null
        ): CustomerIOShared = INSTANCE ?: CustomerIOShared(
            diGraph = diGraph ?: CustomerIOSharedComponent()
        ).apply { INSTANCE = this }
    }
}

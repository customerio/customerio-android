package io.customer.sdk

import androidx.annotation.VisibleForTesting
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.CustomerIOShared.Companion.instance
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.util.LogcatLogger

/**
 * Singleton static instance of Customer.io SDK that is initialized exactly when
 * [instance] is called the first time. The class should be lightweight and only
 * be used to hold code that might be required before initializing the SDK.
 * <p/>
 * Some use cases of the class may include:
 * - access selected SDK methods even when SDK is not initialized
 * - contains code that cannot guarantee SDK initialization
 * - notify user and prevent unwanted SDK crashes in case of late initialization
 * - hold callbacks/values that might be needed post-initialization of the SDK
 * - reduce challenges of communication when wrapping the SDK for non native
 * platforms
 *
 * @property diGraph instance of DI graph to satisfy dependencies
 */
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

        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun clearInstance() {
            INSTANCE = null
        }
    }
}

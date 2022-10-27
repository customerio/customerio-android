package io.customer.sdk

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.CustomerIOShared.Companion.instance
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.repository.preference.CustomerIOStoredValues
import io.customer.sdk.util.PreInitializationLogcatLogger

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
 * @property diStaticGraph instance of DI graph to satisfy dependencies
 */
class CustomerIOShared private constructor(
    val diStaticGraph: CustomerIOStaticComponent
) {

    var diSharedGraph: CustomerIOSharedComponent? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun initializeAndGetSharedComponent(context: Context): CustomerIOSharedComponent {
        return diSharedGraph ?: CustomerIOSharedComponent(context).apply {
            diSharedGraph = this
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun attachSDKConfig(sdkConfig: CustomerIOConfig, context: Context) {
        (diStaticGraph.logger as? PreInitializationLogcatLogger)?.setPreferredLogLevel(logLevel = sdkConfig.logLevel)
        initializeAndGetSharedComponent(context)
        diSharedGraph?.sharedPreferenceRepository?.saveSettings(
            CustomerIOStoredValues(customerIOConfig = sdkConfig)
        )
    }

    companion object {
        private var INSTANCE: CustomerIOShared? = null

        @JvmStatic
        fun instance(): CustomerIOShared = createInstance(diStaticGraph = null)

        @Synchronized
        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun createInstance(
            diStaticGraph: CustomerIOStaticComponent? = null
        ): CustomerIOShared = INSTANCE ?: CustomerIOShared(
            diStaticGraph = diStaticGraph ?: CustomerIOStaticComponent()
        ).apply { INSTANCE = this }

        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun clearInstance() {
            INSTANCE = null
        }
    }
}

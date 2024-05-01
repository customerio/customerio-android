// TODO: Move this class and its dependencies (CustomerIOInstance) to the correct package.
// We need to move this class to the right package to avoid breaking imports for the users of the SDK.
// We have placed the class in the wrong package for now to avoid breaking the build.
// Once old implementations are removed, we can move the class to the correct package.
package io.customer.sdk.android

import androidx.annotation.VisibleForTesting
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent

/**
 * Welcome to the Customer.io Android SDK!
 * This class is where you begin to use the SDK.
 * You must have an instance of `CustomerIO` to use the features of the SDK.
 * Create your own instance using
 * ```
 * with(CustomerIOBuilder(appContext: Application context, cdpApiKey = "XXX")) {
 *   setLogLevel(...)
 *   addCustomerIOModule(...)
 *   build()
 * }
 * ```
 * It is recommended to initialize the client in the `Application::onCreate()` method.
 * After the instance is created you can access it via singleton instance: `CustomerIO.instance()` anywhere,
 */
class CustomerIO private constructor(
    implementation: CustomerIOInstance
) : CustomerIOInstance by implementation {
    companion object {
        /**
         * Singleton instance of CustomerIO SDK that is created and set using the provided implementation.
         */
        @Volatile
        private var instance: CustomerIO? = null

        /**
         * Returns the instance of CustomerIO SDK.
         * If the instance is not initialized, it will throw an exception.
         * Please ensure that the SDK is initialized before calling this method.
         */
        @JvmStatic
        fun instance(): CustomerIO {
            return instance ?: throw IllegalStateException(
                "CustomerIO is not initialized. CustomerIOBuilder::build() must be called before obtaining SDK instance."
            )
        }

        /**
         * Creates and sets new instance of CustomerIO SDK using the provided implementation.
         * If the instance is already initialized, it will log an error and skip the initialization.
         * This method should be called only once during the application lifecycle using the provided builder.
         */
        @Synchronized
        @InternalCustomerIOApi
        fun createInstance(
            implementation: CustomerIOInstance
        ): CustomerIO {
            val existingInstance = instance
            if (existingInstance != null) {
                SDKComponent.logger.error("CustomerIO instance is already initialized, skipping the initialization.")
                return existingInstance
            }
            return CustomerIO(implementation = implementation).apply {
                instance = this
            }
        }

        /**
         * Clears the instance of CustomerIO SDK.
         * This method is used for testing purposes only and should not be used in production.
         */
        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun clearInstance() {
            instance = null
        }
    }
}

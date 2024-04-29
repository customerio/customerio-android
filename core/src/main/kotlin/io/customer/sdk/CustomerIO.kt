package io.customer.sdk

import androidx.annotation.VisibleForTesting
import io.customer.base.internal.InternalCustomerIOApi

class CustomerIO(implementation: CustomerIOInstance) : CustomerIOInstance by implementation {
    companion object {
        private var instance: CustomerIO? = null

        @JvmStatic
        fun instance(): CustomerIO {
            return instance ?: throw IllegalStateException(
                "CustomerIO is not initialized. CustomerIO SDK must be called before obtaining CustomerIO instance."
            )
        }

        @InternalCustomerIOApi
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        fun clearInstance() {
            instance = null
        }
    }
}

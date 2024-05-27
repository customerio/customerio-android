package io.customer.datapipelines.support.utils

/**
 * Constants to help with testing and avoid code duplication in tests.
 * The purpose of this class is to store constants that are used in multiple tests.
 * If the constant value changes, it only needs to be updated in one place but will
 * reflect in all tests and help validate the changes.
 */
internal object TestConstants {
    object Events {
        const val DEVICE_CREATED = "Device Created or Updated"
        const val DEVICE_DELETED = "Device Deleted"
    }
}

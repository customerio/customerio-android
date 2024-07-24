package io.customer.commontest.core

/**
 * Constants to help with testing and avoid code duplication in tests.
 * The purpose of this class is to store constants that are used in multiple tests.
 * If the constant value changes, it only needs to be updated in one place but will
 * reflect in all tests and help validate the changes.
 */
object TestConstants {
    object Keys {
        const val SITE_ID = "TESTING_SITE_ID"
        const val CDP_API_KEY = "TESTING_API_KEY"
    }
}

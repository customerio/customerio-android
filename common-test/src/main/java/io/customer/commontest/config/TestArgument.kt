package io.customer.commontest.config

import android.app.Application

/**
 * Base interface for all test arguments.
 * A test argument is a value that can be passed to a test configuration.
 * Strong typing of arguments allows for easy configuration of test environments
 * and minimizes the risk of passing incorrect arguments to tests.
 */
interface TestArgument

/**
 * Argument for passing application instance to test configuration.
 */
data class ApplicationArgument(
    val value: Application
) : TestArgument

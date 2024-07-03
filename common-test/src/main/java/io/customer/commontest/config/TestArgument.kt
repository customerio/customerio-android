package io.customer.commontest.config

import android.app.Application
import io.customer.sdk.data.store.Client

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

/**
 * Argument for passing client instance to test configuration.
 */
data class ClientArgument(
    val value: Client = Client.Android(sdkVersion = "3.0.0")
) : TestArgument

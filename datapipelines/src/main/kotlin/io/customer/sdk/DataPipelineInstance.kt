package io.customer.sdk

import io.customer.sdk.android.CustomerIO
import io.customer.sdk.android.CustomerIOInstance

/**
 * Allows mocking of [CustomerIO] for automated tests in the project.
 * Mock the implementation of this interface to test the behavior of the SDK without actually calling the SDK.
 */
interface DataPipelineInstance : CustomerIOInstance

package io.customer.datapipelines.extensions

import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent

/**
 * Gets the AndroidSDKComponent instance from the SDKComponent object or throw an exception
 * if it is not initialized.
 */
fun SDKComponent.requireAndroidSDKComponent(): AndroidSDKComponent = requireNotNull(androidSDKComponent) {
    "AndroidSDKComponent is not initialized. Make sure to call initialize CustomerIO SDK using context before accessing it."
}

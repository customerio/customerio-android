package io.customer.sdk.core.di

import android.content.Context

/**
 * The file contains extension functions for the SDKComponent object and its dependencies.
 */

/**
 * Create and register an instance of AndroidSDKComponent with the provided context,
 * only if it is not already initialized.
 * This function should be called from all entry points of the SDK to ensure that
 * AndroidSDKComponent is initialized before accessing any of its dependencies.
 */
fun SDKComponent.setupAndroidComponent(
    context: Context
) = registerDependency<AndroidSDKComponent> {
    AndroidSDKComponentImpl(context)
}

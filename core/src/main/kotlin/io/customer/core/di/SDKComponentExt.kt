package io.customer.core.di

import android.content.Context

/**
 * The file contains extension functions for the SDKComponent object and its dependencies.
 */

/**
 * Create and register an instance of AndroidSDKComponent with the provided context,
 * only if it is not already initialized.
 */
fun SDKComponent.registerAndroidSDKComponent(context: Context) = registerDependency {
    AndroidSDKComponent(context)
}

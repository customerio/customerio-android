package io.customer.sdk.core.di

import android.content.Context
import io.customer.sdk.data.store.Client

/**
 * The file contains extension functions for the SDKComponent object and its dependencies.
 */

/**
 * Create and register an instance of AndroidSDKComponent with the provided context,
 * only if it is not already initialized.
 */
fun SDKComponent.registerAndroidSDKComponent(
    context: Context,
    client: Client
registerDependency<AndroidSDKComponent> {
    AndroidSDKComponentImpl(context, client)
}

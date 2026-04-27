package io.customer.sdk.core.di

import android.content.Context
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.CustomerIOHttpClientImpl
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider

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

@InternalCustomerIOApi
val SDKComponent.httpClient: CustomerIOHttpClient
    get() = singleton<CustomerIOHttpClient> { CustomerIOHttpClientImpl() }

@InternalCustomerIOApi
val SDKComponent.workManagerProvider: CustomerIOWorkManagerProvider
    get() = singleton<CustomerIOWorkManagerProvider> { CustomerIOWorkManagerProvider(android().applicationContext, logger) }

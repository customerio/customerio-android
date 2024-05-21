package io.customer.sdk.core.di

import android.content.Context
import io.customer.sdk.data.store.ApplicationStore
import io.customer.sdk.data.store.ApplicationStoreImpl
import io.customer.sdk.data.store.BuildStore
import io.customer.sdk.data.store.BuildStoreImpl
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceStoreImpl

/**
 * DIGraph component for Android-specific dependencies to ensure all SDK
 * modules can access them.
 * Integrate this graph at SDK startup using from Android entry point.
 */
class AndroidSDKComponent(
    val context: Context,
    val client: Client
) : DiGraph() {
    val applicationContext: Context
        get() = newInstance { context.applicationContext }
    val buildStore: BuildStore
        get() = newInstance { BuildStoreImpl() }
    val applicationStore: ApplicationStore
        get() = newInstance { ApplicationStoreImpl(context = applicationContext) }
    val deviceStore: DeviceStore
        get() = newInstance {
            DeviceStoreImpl(
                buildStore = buildStore,
                applicationStore = applicationStore,
                client = client
            )
        }
}

package io.customer.sdk.core.di

import android.app.Application
import android.content.Context
import io.customer.sdk.data.store.ApplicationStore
import io.customer.sdk.data.store.ApplicationStoreImpl
import io.customer.sdk.data.store.BuildStore
import io.customer.sdk.data.store.BuildStoreImpl
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.DeviceStoreImpl
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.data.store.GlobalPreferenceStoreImpl

abstract class AndroidSDKComponent : DiGraph() {
    abstract val client: Client
    abstract val application: Application
    abstract val applicationContext: Context
    abstract val buildStore: BuildStore
    abstract val applicationStore: ApplicationStore
    abstract val deviceStore: DeviceStore
    abstract val globalPreferenceStore: GlobalPreferenceStore
}

/**
 * DIGraph component for Android-specific dependencies to ensure all SDK
 * modules can access them.
 * Integrate this graph at SDK startup using from Android entry point.
 */
class AndroidSDKComponentImpl(
    private val context: Context,
    override val client: Client
) : AndroidSDKComponent() {
    override val application: Application
        get() = newInstance<Application> { context.applicationContext as Application }
    override val applicationContext: Context
        get() = newInstance<Context> { context.applicationContext }
    override val buildStore: BuildStore
        get() = newInstance<BuildStore> { BuildStoreImpl() }
    override val applicationStore: ApplicationStore
        get() = newInstance<ApplicationStore> { ApplicationStoreImpl(context = applicationContext) }
    override val deviceStore: DeviceStore
        get() = newInstance<DeviceStore> {
            DeviceStoreImpl(
                buildStore = buildStore,
                applicationStore = applicationStore,
                client = client
            )
        }
    override val globalPreferenceStore: GlobalPreferenceStore
        get() = singleton<GlobalPreferenceStore> { GlobalPreferenceStoreImpl(applicationContext) }
}

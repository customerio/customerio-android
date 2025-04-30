package io.customer.sdk.core.di

import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.EventBusImpl
import io.customer.sdk.core.environment.BuildEnvironment
import io.customer.sdk.core.environment.DefaultBuildEnvironment
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.LoggerImpl
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.core.util.SdkDispatchers
import io.customer.sdk.core.util.SdkScopeProvider
import io.customer.sdk.lifecycle.CustomerIOActivityLifecycleCallbacks

/**
 * Object level DiGraph for the SDK. Provides a centralized way to manage all
 * dependencies in the SDK in a single place.
 * The object can be accessed from anywhere in the SDK and can be used to provide
 * a consistent and efficient way to access them throughout the SDK.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object SDKComponent : DiGraph() {
    // Static map to store all the modules registered with the SDK
    val modules: MutableMap<String, CustomerIOModule<*>> = mutableMapOf()

    // Android component dependencies
    val activityLifecycleCallbacks: CustomerIOActivityLifecycleCallbacks
        get() = singleton<CustomerIOActivityLifecycleCallbacks> { CustomerIOActivityLifecycleCallbacks() }
    internal val androidSDKComponent: AndroidSDKComponent?
        get() = getOrNull()

    /**
     * Public method to access non-null instance of Android specific dependencies.
     * AndroidSDKComponent should never be null if CustomerIO is initialized before accessing it.
     * If CustomerIO is not initialized, it will throw an IllegalStateException.
     */
    fun android(): AndroidSDKComponent {
        return androidSDKComponent ?: throw IllegalStateException("AndroidSDKComponent is not initialized. Make sure to initialize SDK components with context before accessing it.")
    }

    // Core dependencies
    val buildEnvironment: BuildEnvironment
        get() = newInstance<BuildEnvironment> { DefaultBuildEnvironment() }
    val logger: Logger
        get() = singleton<Logger> { LoggerImpl(buildEnvironment = buildEnvironment) }

    // Communication dependencies
    val eventBus: EventBus
        get() = singleton<EventBus> { EventBusImpl() }
    val dispatchersProvider: DispatchersProvider
        get() = newInstance<DispatchersProvider> { SdkDispatchers() }
    val scopeProvider: ScopeProvider
        get() = newInstance<ScopeProvider> { SdkScopeProvider(dispatchersProvider) }

    override fun reset() {
        androidSDKComponent?.reset()
        modules.clear()
        eventBus.removeAllSubscriptions()

        super.reset()
    }
}

package io.customer.sdk.core.di

import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.EventBusImpl
import io.customer.sdk.core.environment.BuildEnvironment
import io.customer.sdk.core.environment.DefaultBuildEnvironment
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.LogcatLogger
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.core.util.SdkDispatchers
import io.customer.sdk.core.util.SdkScopeProvider

/**
 * Object level DiGraph for the SDK. Provides a centralized way to manage all
 * dependencies in the SDK in a single place.
 * The object can be accessed from anywhere in the SDK and can be used to provide
 * a consistent and efficient way to access them throughout the SDK.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object SDKComponent : DiGraph() {
    val androidSDKComponent: AndroidSDKComponent? get() = getOrNull()
    val buildEnvironment: BuildEnvironment get() = newInstance<BuildEnvironment> { DefaultBuildEnvironment() }
    val logger: Logger get() = singleton<Logger> { LogcatLogger(buildEnvironment = buildEnvironment) }
    val modules: MutableMap<String, CustomerIOModule<*>> = mutableMapOf()
    val eventBus: EventBus get() = singleton<EventBus> { EventBusImpl() }
    val dispatchersProvider: DispatchersProvider get() = newInstance { SdkDispatchers() }
    val scopeProvider: ScopeProvider get() = newInstance { SdkScopeProvider(dispatchersProvider) }

    override fun reset() {
        androidSDKComponent?.reset()
        modules.clear()

        super.reset()
    }
}

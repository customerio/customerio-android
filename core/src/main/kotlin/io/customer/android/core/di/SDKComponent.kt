package io.customer.android.core.di

import io.customer.android.core.environment.BuildEnvironment
import io.customer.android.core.environment.DefaultBuildEnvironment
import io.customer.android.core.module.CustomerIOModule
import io.customer.android.core.util.LogcatLogger
import io.customer.android.core.util.Logger

/**
 * Object level DiGraph for the SDK. Provides a centralized way to manage all
 * dependencies in the SDK in a single place.
 * The object can be accessed from anywhere in the SDK and can be used to provide
 * a consistent and efficient way to access them throughout the SDK.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object SDKComponent : DiGraph() {
    val androidSDKComponent: AndroidSDKComponent? get() = getOrNull()
    val buildEnvironment: BuildEnvironment get() = newInstance { DefaultBuildEnvironment() }
    val logger: Logger get() = singleton { LogcatLogger(buildEnvironment = buildEnvironment) }
    val modules: MutableMap<String, CustomerIOModule<*>> = mutableMapOf()
}

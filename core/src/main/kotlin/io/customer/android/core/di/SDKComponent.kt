package io.customer.android.core.di

import io.customer.android.core.module.CustomerIOModule

/**
 * Object level DiGraph for the SDK. Provides a centralized way to manage all
 * dependencies in the SDK in a single place.
 * The object can be accessed from anywhere in the SDK and can be used to provide
 * a consistent and efficient way to access them throughout the SDK.
 */
object SDKComponent : DiGraph() {
    val androidSDKComponent: AndroidSDKComponent? get() = getOrNull()
    val modules: MutableMap<String, CustomerIOModule<*>> = mutableMapOf()
}

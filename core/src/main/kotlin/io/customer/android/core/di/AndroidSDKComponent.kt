package io.customer.android.core.di

import android.content.Context

/**
 * DIGraph component for Android-specific dependencies to ensure all SDK
 * modules can access them.
 * Integrate this graph at SDK startup using from Android entry point.
 */
class AndroidSDKComponent(
    val context: Context
) : DiGraph() {
    val applicationContext: Context
        get() = newInstance { context.applicationContext }
}

package io.customer.messaginginapp.ui.bridge

import android.app.Activity
import android.view.ViewGroup
import io.customer.messaginginapp.ui.lifecycle.ActivityLifecycleProvider
import io.customer.messaginginapp.ui.lifecycle.LifecycleProvider

/**
 * Implementation of [InAppHostViewDelegate] specifically for modal in-app messages.
 * Uses the Activity lifecycle instead of the View lifecycle.
 */
internal class ModalInAppHostViewDelegate(
    private val activity: Activity,
    private val view: ViewGroup
) : InAppHostViewDelegateImpl(view) {

    override fun createLifecycleProvider(): LifecycleProvider {
        return ActivityLifecycleProvider(activity)
    }
}

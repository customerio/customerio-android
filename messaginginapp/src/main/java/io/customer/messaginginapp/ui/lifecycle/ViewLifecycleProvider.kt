package io.customer.messaginginapp.ui.lifecycle

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * Implementation of [LifecycleProvider] that uses a View's lifecycle.
 * This is appropriate for inline messages that should respond to view lifecycle events
 * rather than activity lifecycle events.
 */
internal class ViewLifecycleProvider(
    private val view: View
) : LifecycleProvider {

    private val viewLifecycleOwner: LifecycleOwner?
        get() = view.findViewTreeLifecycleOwner()

    override fun addObserver(observer: LifecycleObserver): Boolean {
        val lifecycleOwner = viewLifecycleOwner ?: return false
        lifecycleOwner.lifecycle.addObserver(observer)
        return true
    }

    override fun removeObserver(observer: LifecycleObserver) {
        viewLifecycleOwner?.lifecycle?.removeObserver(observer)
    }

    override fun getLifecycleState(): Lifecycle.State? {
        return viewLifecycleOwner?.lifecycle?.currentState
    }
}

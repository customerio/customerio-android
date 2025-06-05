package io.customer.messaginginapp.ui.controller

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Base class that enforces thread-safe property patterns for controller classes.
 * All controller properties that might be accessed cross-thread should use these delegates.
 */
internal abstract class ThreadSafeController {

    /**
     * Property delegate that automatically makes properties thread-safe with @Volatile.
     * Use this for simple properties that don't need complex state management.
     *
     * Usage:
     * var elementId: String? by threadSafe()
     * var isVisible: Boolean by threadSafe(false)
     */
    protected fun <T> threadSafe(initialValue: T? = null): ReadWriteProperty<Any?, T?> {
        return VolatileProperty(initialValue)
    }

    /**
     * Property delegate with change notifications for properties that trigger actions.
     *
     * Usage:
     * var elementId: String? by threadSafeWithNotification { old, new ->
     *     if (old != new) onElementIdChanged(old, new)
     * }
     */
    protected fun <T> threadSafeWithNotification(
        initialValue: T? = null,
        onChange: (old: T?, new: T?) -> Unit
    ): ReadWriteProperty<Any?, T?> {
        return VolatilePropertyWithNotification(initialValue, onChange)
    }
}

/**
 * Thread-safe property delegate using @Volatile
 */
private class VolatileProperty<T>(initialValue: T?) : ReadWriteProperty<Any?, T?> {
    @Volatile
    private var value: T? = initialValue

    override fun getValue(thisRef: Any?, property: KProperty<*>): T? = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        this.value = value
    }
}

/**
 * Thread-safe property delegate with change notifications
 */
private class VolatilePropertyWithNotification<T>(
    initialValue: T?,
    private val onChange: (old: T?, new: T?) -> Unit
) : ReadWriteProperty<Any?, T?> {
    @Volatile
    private var value: T? = initialValue

    override fun getValue(thisRef: Any?, property: KProperty<*>): T? = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        val oldValue = this.value
        this.value = value
        onChange(oldValue, value)
    }
}

/**
 * Annotation to mark properties that should be thread-safe
 * This helps with code reviews and static analysis
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ThreadSafeProperty(
    val reason: String = "Accessed from multiple threads"
)

package io.customer.messaginginapp.ui.bridge

import android.view.View
import io.customer.base.internal.InternalCustomerIOApi

/**
 * Platform delegate that eliminates duplication between Flutter and React Native.
 * Contains all the common animation and event handling logic that was duplicated.
 *
 * This abstract class extends AndroidInAppPlatformDelegate and provides a unified interface
 * for cross-platform implementations to handle events and animations consistently.
 * Each platform (Flutter/React Native) needs to implement the dispatchEvent method
 * to route events to their respective bridge systems.
 */
@InternalCustomerIOApi
abstract class WrapperPlatformDelegate(view: View) : AndroidInAppPlatformDelegate(view) {

    companion object {
        /**
         * Event name constant for size change events
         */
        const val ON_SIZE_CHANGE = "onSizeChange"

        /**
         * Event name constant for state change events
         */
        const val ON_STATE_CHANGE = "onStateChange"
    }

    /**
     * Platform-specific event dispatch mechanism.
     * Each platform (Flutter/React Native) implements this to route events to their bridge.
     *
     * @param eventName The name of the event to dispatch (e.g., "onSizeChange", "onStateChange")
     * @param payload The event payload containing relevant data for the event
     */
    protected abstract fun dispatchEvent(eventName: String, payload: Map<String, Any?>)

    /**
     * Animation logic - no more duplication!
     * Handles size animations and dispatches events through platform-specific dispatch.
     *
     * This method overrides the default Android implementation to provide consistent
     * animation behavior across all platforms while still leveraging the underlying
     * Android animation system.
     *
     * @param widthInDp Target width in density-independent pixels, null to keep current width
     * @param heightInDp Target height in density-independent pixels, null to keep current height
     * @param duration Animation duration in milliseconds, null to use default duration
     * @param onStart Callback invoked when animation starts
     * @param onEnd Callback invoked when animation completes
     */
    override fun animateViewSize(
        widthInDp: Double?,
        heightInDp: Double?,
        duration: Long?,
        onStart: (() -> Unit)?,
        onEnd: (() -> Unit)?
    ) {
        onStart?.invoke()

        val animDuration = duration ?: defaultAnimDuration
        val payload = mutableMapOf<String, Any?>()

        // Only include dimensions that are positive values
        widthInDp?.takeIf { it > 0 }?.let { payload["width"] = it }
        heightInDp?.let { payload["height"] = it }
        payload["duration"] = animDuration.toDouble()

        // Use platform-specific dispatch instead of direct method calls
        dispatchEvent(ON_SIZE_CHANGE, payload)

        // Schedule the onEnd callback after animation duration
        onEnd?.let {
            view.postDelayed({ it.invoke() }, animDuration)
        }
    }

    /**
     * Loading state event handling - no more duplication!
     *
     * This method provides a unified way to send loading state events across all platforms.
     * It converts the state enum to a consistent payload format that can be handled
     * by any platform bridge implementation.
     *
     * @param state The current loading state of the in-app message
     */
    fun sendLoadingStateEvent(state: WrapperStateEvent) {
        val payload = mapOf("state" to state.name)
        dispatchEvent(ON_STATE_CHANGE, payload)
    }
}

/**
 * State event enum - shared across all platforms.
 *
 * This enum defines the possible states that an in-app message can be in during its lifecycle.
 * These states are used to provide consistent feedback to the hosting platform about
 * the current status of message loading and display.
 */
@InternalCustomerIOApi
enum class WrapperStateEvent {
    /**
     * Indicates that the in-app message has started loading.
     * This state is triggered when the message begins to load content or resources.
     */
    LoadingStarted,

    /**
     * Indicates that the in-app message has finished loading successfully.
     * This state is triggered when all content and resources have been loaded
     * and the message is ready for display.
     */
    LoadingFinished,

    /**
     * Indicates that there is no message available to display.
     * This state is triggered when no matching in-app message is found
     * for the current context or targeting criteria.
     */
    NoMessageToDisplay
}

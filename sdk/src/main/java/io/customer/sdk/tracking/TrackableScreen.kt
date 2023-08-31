package io.customer.sdk.tracking

/**
 * Interface for activities or fragments that are trackable.
 */
interface TrackableScreen {
    /**
     * Retrieve the name used for tracking the screen.
     *
     * @return Name of the screen for tracking purposes, or null if the screen should not be tracked.
     */
    fun getScreenName(): String?
}

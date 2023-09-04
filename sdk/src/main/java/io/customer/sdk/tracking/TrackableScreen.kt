package io.customer.sdk.tracking

/**
 * Optional interface for activities that should be tracked using automated screen tracking.
 */
interface TrackableScreen {
    /**
     * Retrieve the name that should be used for tracking the screen. This name
     * should be unique for each screen.
     *
     * @return name for tracking the screen, or null if the screen shouldn't be tracked.
     */
    fun getScreenName(): String?
}

package io.customer.datapipelines.config

/**
 * Enum class to define how CustomerIO SDK should handle screen view events.
 */
enum class ScreenView {
    /**
     * Screen view events are sent to destinations for analytics purposes.
     */
    Analytics,

    /**
     * Screen view events are kept on device only. They are used to display in-app messages based on page rules. Events are not sent to our back end servers.
     */
    InApp;

    companion object {
        /**
         * Returns the [ScreenView] enum constant for the given name.
         * Returns fallback if the specified enum type has no constant with the given name.
         * Defaults to [Analytics].
         */
        @JvmOverloads
        fun getScreenView(screenView: String?, fallback: ScreenView = Analytics): ScreenView {
            return values().firstOrNull { it.name.equals(screenView, ignoreCase = true) } ?: fallback
        }
    }
}

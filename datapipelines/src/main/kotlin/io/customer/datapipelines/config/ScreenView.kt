package io.customer.datapipelines.config

/**
 * Enum class to define how CustomerIO SDK should handle screen view events.
 */
sealed class ScreenView(val name: String) {
    /**
     * Screen view events are sent to destinations for analytics purposes.
     */
    object All : ScreenView(name = "all")

    /**
     * Screen view events are kept on device only. They are used to display in-app messages based on page rules. Events are not sent to our back end servers.
     */

    object InApp : ScreenView(name = "inapp")

    companion object {
        /**
         * Returns the [ScreenView] enum constant for the given name.
         * Returns fallback if the specified enum type has no constant with the given name.
         * Defaults to [All].
         */
        @JvmOverloads
        fun getScreenView(screenView: String?, fallback: ScreenView = All): ScreenView {
            if (screenView.isNullOrBlank()) {
                return fallback
            }

            return listOf(
                All,
                InApp
            ).find { value -> value.name.equals(screenView, ignoreCase = true) } ?: fallback
        }
    }
}

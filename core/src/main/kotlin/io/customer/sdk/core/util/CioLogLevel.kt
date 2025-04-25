package io.customer.sdk.core.util

/**
 * Log levels for Customer.io SDK logs
 *
 * @property priority Priority of the log level. The higher the value, the more verbose
 * the log level.
 */
enum class CioLogLevel(val priority: Int) {
    NONE(priority = 0),
    ERROR(priority = 1),
    INFO(priority = 2),
    DEBUG(priority = 3);

    companion object {
        fun getLogLevel(level: String?, fallback: CioLogLevel = ERROR): CioLogLevel {
            return values().find { value -> value.name.equals(level, ignoreCase = true) }
                ?: fallback
        }
    }
}

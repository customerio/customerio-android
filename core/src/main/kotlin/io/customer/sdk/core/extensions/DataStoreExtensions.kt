package io.customer.sdk.core.extensions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Extension function to safely read from DataStore with consistent error handling.
 *
 * As an SDK, we must never crash the host app. All exceptions are caught and logged,
 * with graceful fallback to empty preferences to ensure app stability.
 */
internal fun DataStore<Preferences>.safeData(): Flow<Preferences> {
    val logger: Logger = SDKComponent.logger

    return data.catch { exception ->
        logger.error("DataStore error: ${exception.message}")
        emit(emptyPreferences())
    }
}

/**
 * Safely reads a string value from DataStore with error handling.
 * Returns null if the key doesn't exist or if there are any errors.
 *
 * As an SDK, we prioritize app stability over data completeness.
 */
internal suspend fun DataStore<Preferences>.safeGetString(key: Preferences.Key<String>): String? {
    return safeData().map { it[key] }.first()
}

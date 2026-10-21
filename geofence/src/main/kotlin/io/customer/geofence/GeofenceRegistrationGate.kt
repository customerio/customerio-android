package io.customer.geofence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders every write of GMS registrations: repository syncs and resets, and polygon dwell wake arms
 * and restores. A same-id add replaces in place, so an unordered pair could leave the older request
 * live. One per process; see DI.
 *
 * Not reentrant. A holder calls [GeofenceRegistrar] directly, never anything that takes this gate,
 * and never joins a job that may be waiting for it.
 */
internal class GeofenceRegistrationGate {
    /** Exposed so the repository's existing state lock can be this one. */
    val mutex: Mutex = Mutex()

    suspend inline fun <T> withRegistration(block: () -> T): T = mutex.withLock { block() }
}

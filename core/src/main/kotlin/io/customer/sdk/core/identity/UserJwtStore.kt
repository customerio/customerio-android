package io.customer.sdk.core.identity

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent

/**
 * The signed user JWT the app handed over, and the user it was signed for. Data Pipelines sends
 * it with each upload and in-app with each Gist call. Listeners are called on the caller's thread,
 * so a module sees the change before `setUserJwt` returns.
 */
@InternalCustomerIOApi
class UserJwtStore {
    /** A JWT and its user. [toString] never prints the token. */
    data class Entry(val userId: String, val token: String) {
        override fun toString(): String = "Entry(userId=$userId)"
    }

    @Volatile
    var current: Entry? = null
        private set

    private val listeners = mutableListOf<(Entry?) -> Unit>()

    fun set(entry: Entry?) {
        val listenersToCall = synchronized(listeners) {
            current = entry
            listeners.toList()
        }
        listenersToCall.forEach { it(entry) }
    }

    /** Calls [listener] on every change after this call; read [current] for the value now. */
    fun addListener(listener: (Entry?) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }
}

@InternalCustomerIOApi
val SDKComponent.userJwtStore: UserJwtStore
    get() = singleton<UserJwtStore> { UserJwtStore() }

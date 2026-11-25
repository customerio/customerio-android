package io.customer.sdk.core.identity

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent
import java.util.concurrent.CopyOnWriteArrayList

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

    private val listeners = CopyOnWriteArrayList<(Entry?) -> Unit>()
    private val notifyLock = Any()

    fun set(entry: Entry?) {
        current = entry
        // Listeners get the value at call time, not [entry], one notification at a time: two close
        // calls could otherwise reach a listener in the wrong order and leave it on the older token.
        synchronized(notifyLock) {
            listeners.forEach { it(current) }
        }
    }

    /** Calls [listener] on every change after this call; [replayCurrent] hands it the value now. */
    fun addListener(listener: (Entry?) -> Unit) {
        listeners += listener
    }

    /** Calls [listener] with [current], in order with the notifications from [set]. */
    fun replayCurrent(listener: (Entry?) -> Unit) {
        synchronized(notifyLock) { listener(current) }
    }
}

@InternalCustomerIOApi
val SDKComponent.userJwtStore: UserJwtStore
    get() = singleton<UserJwtStore> { UserJwtStore() }

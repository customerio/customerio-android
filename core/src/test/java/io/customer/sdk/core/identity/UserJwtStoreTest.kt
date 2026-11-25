package io.customer.sdk.core.identity

import io.customer.commontest.core.JUnit5Test
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class UserJwtStoreTest : JUnit5Test() {

    @Test
    fun set_givenNewerTokenSetDuringANotification_everyListenerEndsOnTheNewerToken() {
        val store = UserJwtStore()
        val older = UserJwtStore.Entry("alex", "jwt-1")
        val newer = UserJwtStore.Entry("alex", "jwt-2")
        store.addListener { if (it == older) store.set(newer) }
        val received = mutableListOf<UserJwtStore.Entry?>()
        store.addListener { received += it }

        store.set(older)

        received.last() shouldBeEqualTo newer
        store.current shouldBeEqualTo newer
    }
}

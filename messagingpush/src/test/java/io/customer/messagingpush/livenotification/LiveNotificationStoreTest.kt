package io.customer.messagingpush.livenotification

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationStoreTest : IntegrationTest() {

    private val store by lazy { LiveNotificationStore(contextMock) }

    @Test
    fun registrationSignature_setGetClear() {
        store.registrationSignature("type-a").shouldBeNull()

        store.setRegistrationSignature("type-a", "tok|user")
        store.setRegistrationSignature("type-b", "tok|user")

        store.registrationSignature("type-a") shouldBeEqualTo "tok|user"

        store.clearRegistrations()

        store.registrationSignature("type-a").shouldBeNull()
        store.registrationSignature("type-b").shouldBeNull()
    }

    @Test
    fun lastTimestamp_setGetClear() {
        store.lastTimestamp("act-1").shouldBeNull()

        store.setLastTimestamp("act-1", 1_000L)
        store.lastTimestamp("act-1") shouldBeEqualTo 1_000L

        store.clearTimestamp("act-1")
        store.lastTimestamp("act-1").shouldBeNull()
    }

    @Test
    fun trimStaleTimestamps_removesEntriesOlderThanTtl() {
        val now = 10_000_000_000L
        val ttl = 1_000L

        // Recorded before the cutoff -> stale.
        store.setLastTimestamp("old", 1L, now = now - ttl - 1)
        // Recorded within the ttl -> kept.
        store.setLastTimestamp("fresh", 2L, now = now - 1)

        store.trimStaleTimestamps(ttlMs = ttl, now = now)

        store.lastTimestamp("old").shouldBeNull()
        store.lastTimestamp("fresh") shouldBeEqualTo 2L
    }
}

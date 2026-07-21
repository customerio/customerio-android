package io.customer.messagingpush.livenotification

import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
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

    @Test
    fun activityType_setGetClear() {
        store.activityType("act-1").shouldBeNull()

        store.setActivityType("act-1", "io.customer.livenotifications.segments")
        store.activityType("act-1") shouldBeEqualTo "io.customer.livenotifications.segments"

        store.clearActivityType("act-1")
        store.activityType("act-1").shouldBeNull()
    }

    @Test
    fun trackedActivityIds_andClearAllActivities() {
        store.setActivityType("a1", "type.a")
        store.setActivityType("a2", "type.b")
        store.setLastTimestamp("a1", 5L)

        store.trackedActivityIds() shouldContainSame setOf("a1", "a2")

        store.clearAllActivities()

        store.trackedActivityIds().shouldBeEmpty()
        store.activityType("a1").shouldBeNull()
        store.lastTimestamp("a1").shouldBeNull()
    }

    @Test
    fun trimStaleTimestamps_alsoRemovesPairedActivityType() {
        val now = 10_000_000_000L
        val ttl = 1_000L

        store.setLastTimestamp("old", 1L, now = now - ttl - 1)
        store.setActivityType("old", "io.customer.livenotifications.segments")

        store.trimStaleTimestamps(ttlMs = ttl, now = now)

        store.activityType("old").shouldBeNull()
    }

    @Test
    fun markEnded_claimsOnceAndReportsTerminalState() {
        store.isEnded("act-1").shouldBeFalse()

        // First mark wins (claims the terminal transition); repeats return false.
        store.markEnded("act-1").shouldBeTrue()
        store.isEnded("act-1").shouldBeTrue()
        store.markEnded("act-1").shouldBeFalse()
        store.markEnded("act-1").shouldBeFalse()
    }

    @Test
    fun clearAllActivities_removesEndedMarker() {
        store.markEnded("a1")
        store.setActivityType("a1", "type.a")

        store.clearAllActivities()

        store.isEnded("a1").shouldBeFalse()
    }

    @Test
    fun trimStaleTimestamps_alsoRemovesEndedMarker() {
        val now = 10_000_000_000L
        val ttl = 1_000L

        store.setLastTimestamp("old", 1L, now = now - ttl - 1)
        store.markEnded("old")

        store.trimStaleTimestamps(ttlMs = ttl, now = now)

        store.isEnded("old").shouldBeFalse()
    }

    @Test
    fun migrate_clearsOldNamespaceRegistrationsAndKeepsNewOnes() {
        // Legacy registrations recorded under the old `io.customer.liveactivities.*` namespace...
        store.setRegistrationSignature("io.customer.liveactivities.deliverytracking", "tok|user")
        store.setRegistrationSignature("io.customer.liveactivities.auctionbid", "tok|user")
        // ...alongside new-namespace and custom-type registrations that must survive.
        store.setRegistrationSignature("io.customer.livenotifications.segments", "tok|user")
        store.setRegistrationSignature("com.acme.custom", "tok|user")

        store.migrate()

        store.registrationSignature("io.customer.liveactivities.deliverytracking").shouldBeNull()
        store.registrationSignature("io.customer.liveactivities.auctionbid").shouldBeNull()
        store.registrationSignature("io.customer.livenotifications.segments") shouldBeEqualTo "tok|user"
        store.registrationSignature("com.acme.custom") shouldBeEqualTo "tok|user"
    }

    @Test
    fun migrate_isIdempotentAndSafeWhenNothingStale() {
        store.setRegistrationSignature("io.customer.livenotifications.segments", "tok|user")

        store.migrate()
        store.migrate()

        store.registrationSignature("io.customer.livenotifications.segments") shouldBeEqualTo "tok|user"
    }
}

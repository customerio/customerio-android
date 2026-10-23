package io.customer.messaginginapp.gist.data.listeners

import io.customer.messaginginapp.state.InAppMessagingState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueueFetchCoordinatorTest {
    private val sut = QueueFetchCoordinator()

    private val anon = QueueFetchKey(userToken = "anon-1", isAnonymous = true)
    private val user = QueueFetchKey(userToken = "user-1", isAnonymous = false)

    // QueueFetchKey

    @Test
    fun key_givenUserIdAndAnonymousId_expectUserIdentity() {
        val key = QueueFetchKey.from(InAppMessagingState(userId = "user-1", anonymousId = "anon-1"))

        assertEquals(QueueFetchKey(userToken = "user-1", isAnonymous = false), key)
    }

    @Test
    fun key_givenOnlyAnonymousId_expectAnonymousIdentity() {
        val key = QueueFetchKey.from(InAppMessagingState(anonymousId = "anon-1"))

        assertEquals(QueueFetchKey(userToken = "anon-1", isAnonymous = true), key)
    }

    @Test
    fun key_givenNoIdentifier_expectNull() {
        assertNull(QueueFetchKey.from(InAppMessagingState()))
    }

    // Sharing an in-flight request

    @Test
    fun begin_givenNothingInFlight_expectTicket() {
        assertNotNull(sut.begin(anon, mustBeFresh = false))
    }

    @Test
    fun begin_givenSameIdentityInFlight_expectShared() {
        sut.begin(anon, mustBeFresh = false)

        assertNull(sut.begin(anon, mustBeFresh = false))
        assertNull(sut.begin(anon, mustBeFresh = true))
    }

    @Test
    fun begin_givenPreviousRequestFinished_expectNewTicket() {
        val first = sut.begin(anon, mustBeFresh = false)!!
        sut.finish(first)

        val second = sut.begin(anon, mustBeFresh = false)

        assertNotNull(second)
        assertNotSame(first, second)
    }

    @Test
    fun begin_givenConcurrentCallersForSameIdentity_expectSingleTicket() {
        val tickets = Collections.synchronizedList(mutableListOf<QueueFetchCoordinator.Ticket>())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        repeat(100) { index ->
            executor.execute {
                start.await()
                sut.begin(anon, mustBeFresh = index % 2 == 0)?.let { tickets.add(it) }
            }
        }
        start.countDown()
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, tickets.size)
    }

    // Callers that need newer data

    @Test
    fun finish_givenOnlyCallersThatAcceptSharedData_expectNoRefetch() {
        val ticket = sut.begin(anon, mustBeFresh = false)!!
        sut.begin(anon, mustBeFresh = false)

        assertFalse(sut.finish(ticket))
    }

    @Test
    fun finish_givenCallersThatNeedNewerData_expectOneRefetch() {
        val ticket = sut.begin(anon, mustBeFresh = false)!!
        sut.begin(anon, mustBeFresh = true)
        sut.begin(anon, mustBeFresh = true)

        assertTrue(sut.finish(ticket))

        // The follow-up is a fresh request: owing nothing once it finishes.
        val followUp = sut.begin(anon, mustBeFresh = false)!!
        assertFalse(sut.finish(followUp))
    }

    @Test
    fun finish_givenFirstCallerNeededNewerData_expectNoRefetch() {
        // The request was started by the caller that needed newer data, so it already satisfies it.
        val ticket = sut.begin(anon, mustBeFresh = true)!!

        assertFalse(sut.finish(ticket))
    }

    // Identity changes

    @Test
    fun begin_givenDifferentIdentityInFlight_expectNewTicket() {
        sut.begin(anon, mustBeFresh = false)

        assertNotNull(sut.begin(user, mustBeFresh = false))
    }

    @Test
    fun finish_givenRequestReplacedByNewIdentity_expectOldTicketCannotReleaseSlot() {
        val anonTicket = sut.begin(anon, mustBeFresh = false)!!
        val userTicket = sut.begin(user, mustBeFresh = false)!!

        assertFalse(sut.finish(anonTicket))
        // The identified user's request is still in flight, so it is still shared.
        assertNull(sut.begin(user, mustBeFresh = false))

        sut.finish(userTicket)
        assertNotNull(sut.begin(user, mustBeFresh = false))
    }

    @Test
    fun finish_givenRefetchOwedToPreviousIdentity_expectNotCarriedOver() {
        sut.begin(anon, mustBeFresh = false)
        sut.begin(anon, mustBeFresh = true)

        val userTicket = sut.begin(user, mustBeFresh = false)!!

        assertFalse(sut.finish(userTicket))
    }

    @Test
    fun begin_givenIdentitySwitchesBackWhileInFlight_expectOldTicketCannotReleaseSlot() {
        val firstAnon = sut.begin(anon, mustBeFresh = false)!!
        sut.begin(user, mustBeFresh = false)
        val secondAnon = sut.begin(anon, mustBeFresh = false)!!

        // The first anonymous request finishing late must not free the slot the second one holds.
        assertFalse(sut.finish(firstAnon))
        assertNull(sut.begin(anon, mustBeFresh = false))

        sut.finish(secondAnon)
        assertNotNull(sut.begin(anon, mustBeFresh = false))
    }
}

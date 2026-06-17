package io.customer.messaginginbox

import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * Placeholder unit test that keeps the `:messaginginbox` module in the unit-test CI matrix while
 * it is an empty opt-in skeleton (no public API yet). The real test suite lands alongside the
 * visual inbox UI in a follow-up.
 */
class MessagingInboxModuleTest {
    @Test
    fun moduleSkeleton_isAccessible() {
        MessagingInbox.shouldNotBeNull()
    }
}

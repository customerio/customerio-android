package io.customer.messaginginbox

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * Placeholder unit test that keeps the `:messaginginbox` module in the unit-test CI matrix on the
 * overlay PR (the module's tests would otherwise be empty here, since the full suite — decoder and
 * controller tests — lands in the stacked tests PR). Exercises pure data-layer logic only; no
 * Compose runtime.
 */
class MessagingInboxModuleTest {
    @Test
    fun unopenedInboxCount_emptyList_isZero() {
        unopenedInboxCount(emptyList()) shouldBeEqualTo 0
    }
}

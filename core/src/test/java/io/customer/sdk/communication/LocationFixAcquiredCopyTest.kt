@file:OptIn(io.customer.base.internal.InternalCustomerIOApi::class)

package io.customer.sdk.communication

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * How source `copy` calls bind once [Event.LocationFixAcquired] carries accuracy: the generated
 * copy and the earlier shape's copy both keep it.
 */
class LocationFixAcquiredCopyTest {
    private val accurate = Event.LocationFixAcquired(1.0, 2.0, 3L, 12f)

    @Test
    fun copyNamingOneProperty_expectAccuracyKept() {
        accurate.copy(longitude = 6.0) shouldBeEqualTo Event.LocationFixAcquired(1.0, 6.0, 3L, 12f)
    }

    @Test
    fun copyWithTheEarlierThreeArguments_expectAccuracyKept() {
        accurate.copy(5.0, 6.0, 7L) shouldBeEqualTo Event.LocationFixAcquired(5.0, 6.0, 7L, 12f)
    }
}

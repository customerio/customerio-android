package io.customer.sdk.core.network

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class ApiVersionStripTest {

    @Test
    fun stripsTheVersionSegmentTheRegionHostsCarry() {
        "cdp.customer.io/v1".withoutApiVersion() shouldBeEqualTo "cdp.customer.io"
        "cdp-eu.customer.io/v1".withoutApiVersion() shouldBeEqualTo "cdp-eu.customer.io"
        "cdp.customer.io/v1/".withoutApiVersion() shouldBeEqualTo "cdp.customer.io"
        "cdp.customer.io/v22".withoutApiVersion() shouldBeEqualTo "cdp.customer.io"
    }

    @Test
    fun leavesAHostThatCarriesNoVersion() {
        "localhost:3000".withoutApiVersion() shouldBeEqualTo "localhost:3000"
        "cdp.customer.io".withoutApiVersion() shouldBeEqualTo "cdp.customer.io"
    }

    /** Anchored at the end: a version inside the name, or mid-path, is part of the host. */
    @Test
    fun leavesAVersionThatIsNotTheLastSegment() {
        "v1.example.com".withoutApiVersion() shouldBeEqualTo "v1.example.com"
        "example.com/v1/edge".withoutApiVersion() shouldBeEqualTo "example.com/v1/edge"
    }
}

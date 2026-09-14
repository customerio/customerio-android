package io.customer.sdk.core.network

import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiVersionStripTest : RobolectricTest() {

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
    // The strip is only real if it changes the URL that is sent. Without these, replacing the whole
    // branch in the client with `settings.apiHost` passes every other test in the repo.

    @Test
    fun composesTheVersionedPathAgainstAVersionlessHost() {
        val url = composeUrl(
            apiHost = "cdp.customer.io/v1",
            params = HttpRequestParams(path = "/v2/geofences/nearest", pathCarriesApiVersion = true)
        )

        url shouldBeEqualTo "https://cdp.customer.io/v2/geofences/nearest"
    }

    @Test
    fun leavesTrackOnTheHostsOwnVersion() {
        val url = composeUrl(
            apiHost = "cdp.customer.io/v1",
            params = HttpRequestParams(path = "/track")
        )

        url shouldBeEqualTo "https://cdp.customer.io/v1/track"
    }

    @Test
    fun stripsTheEuHostsVersionToo() {
        val url = composeUrl(
            apiHost = "cdp-eu.customer.io/v1",
            params = HttpRequestParams(path = "/v2/geofences/nearest", pathCarriesApiVersion = true)
        )

        url shouldBeEqualTo "https://cdp-eu.customer.io/v2/geofences/nearest"
    }

    @Test
    fun keepsQueryParamsAcrossTheSwap() {
        val url = composeUrl(
            apiHost = "cdp.customer.io/v1",
            params = HttpRequestParams(
                path = "/v2/geofences/nearest",
                queryParams = mapOf("limit" to "5"),
                pathCarriesApiVersion = true
            )
        )

        url shouldBeEqualTo "https://cdp.customer.io/v2/geofences/nearest?limit=5"
    }
}

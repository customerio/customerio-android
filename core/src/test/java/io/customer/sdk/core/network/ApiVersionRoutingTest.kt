package io.customer.sdk.core.network

import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiVersionRoutingTest : RobolectricTest() {

    @Test
    fun splitsTheVersionSegmentTheRegionHostsCarry() {
        "cdp.customer.io/v1".splitApiVersion() shouldBeEqualTo ("cdp.customer.io" to "v1")
        "cdp-eu.customer.io/v1".splitApiVersion() shouldBeEqualTo ("cdp-eu.customer.io" to "v1")
        "cdp.customer.io/v1/".splitApiVersion() shouldBeEqualTo ("cdp.customer.io" to "v1")
        "cdp.customer.io/v22".splitApiVersion() shouldBeEqualTo ("cdp.customer.io" to "v22")
    }

    @Test
    fun answersNullForAHostThatCarriesNoVersion() {
        // Null, not a guessed "v1": a self-hosted base we invent a version for would 404.
        "localhost:3000".splitApiVersion() shouldBeEqualTo ("localhost:3000" to null)
        "cdp.customer.io".splitApiVersion() shouldBeEqualTo ("cdp.customer.io" to null)
    }

    @Test
    fun leavesAVersionThatIsNotTheTrailingSegment() {
        "v1.example.com".splitApiVersion() shouldBeEqualTo ("v1.example.com" to null)
        "example.com/v1/edge".splitApiVersion() shouldBeEqualTo ("example.com/v1/edge" to null)
    }

    // The swap is only real if it changes the URL that is sent. Without these, replacing the whole
    // branch in the client with `settings.apiHost` passes every other test in the repo.

    @Test
    fun replacesTheHostVersionWhenTheRequestNamesItsOwn() {
        val url = composeUrl(
            apiHost = "cdp.customer.io/v1",
            params = HttpRequestParams(path = "/geofences/nearest", apiVersion = "v2")
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
    fun replacesRatherThanAppendsWhenTheHostIsAlreadyOnALaterVersion() {
        // A host moved to v3 must not compose /v3/v2. Appending, or stripping a hardcoded "/v1",
        // both produce a URL that 404s here.
        val url = composeUrl(
            apiHost = "cdp.customer.io/v3",
            params = HttpRequestParams(path = "/geofences/nearest", apiVersion = "v2")
        )

        url shouldBeEqualTo "https://cdp.customer.io/v2/geofences/nearest"
    }

    @Test
    fun addsTheRequestedVersionToAHostThatCarriesNone() {
        // The endpoint exists only at v2, so a self-hosted base still has to be asked at v2.
        val url = composeUrl(
            apiHost = "localhost:3000",
            params = HttpRequestParams(path = "/geofences/nearest", apiVersion = "v2")
        )

        url shouldBeEqualTo "https://localhost:3000/v2/geofences/nearest"
    }

    @Test
    fun composesNoVersionSegmentWhenNeitherSideCarriesOne() {
        val url = composeUrl(
            apiHost = "localhost:3000",
            params = HttpRequestParams(path = "/track")
        )

        url shouldBeEqualTo "https://localhost:3000/track"
    }

    @Test
    fun replacesTheEuHostsVersionToo() {
        val url = composeUrl(
            apiHost = "cdp-eu.customer.io/v1",
            params = HttpRequestParams(path = "/geofences/nearest", apiVersion = "v2")
        )

        url shouldBeEqualTo "https://cdp-eu.customer.io/v2/geofences/nearest"
    }

    @Test
    fun toleratesEmptySegmentsInACustomerSuppliedHost() {
        // `apiHost` is customer-supplied, so a stray or doubled slash reaches here verbatim.
        "cdp.customer.io/v1//".splitApiVersion() shouldBeEqualTo ("cdp.customer.io" to "v1")
        "cdp.customer.io//v1".splitApiVersion() shouldBeEqualTo ("cdp.customer.io" to "v1")

        val url = composeUrl(
            apiHost = "cdp.customer.io//v1",
            params = HttpRequestParams(path = "/geofences/nearest", apiVersion = "v2")
        )

        url shouldBeEqualTo "https://cdp.customer.io/v2/geofences/nearest"
    }

    @Test
    fun addsTheSeparatorForAPathThatOmitsIt() {
        val url = composeUrl(
            apiHost = "cdp.customer.io/v1",
            params = HttpRequestParams(path = "geofences/nearest", apiVersion = "v2")
        )

        url shouldBeEqualTo "https://cdp.customer.io/v2/geofences/nearest"
    }

    @Test
    fun keepsQueryParamsAcrossTheSwap() {
        val url = composeUrl(
            apiHost = "cdp.customer.io/v1",
            params = HttpRequestParams(
                path = "/geofences/nearest",
                queryParams = mapOf("limit" to "5"),
                apiVersion = "v2"
            )
        )

        url shouldBeEqualTo "https://cdp.customer.io/v2/geofences/nearest?limit=5"
    }
}

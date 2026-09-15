package io.customer.messagingpush.livenotification

import android.graphics.Color
import android.net.Uri
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip coverage for the branding a cold process reads back. The resource-name indirection is
 * the point: resource ids are build-time values, so persisting ids would resolve to an unrelated
 * drawable after an app update.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationBrandingSerializerTest : IntegrationTest() {

    // Any resource that genuinely exists in the test APK, so getResourceEntryName/getIdentifier
    // have something real to round-trip.
    private val realResId: Int
        get() = android.R.drawable.ic_dialog_info

    private fun encodeThenDecode(branding: LiveNotificationBranding): LiveNotificationBranding? =
        LiveNotificationBrandingSerializer.encode(contextMock, branding)
            ?.let { LiveNotificationBrandingSerializer.decode(contextMock, it) }

    @Test
    fun encodeDecode_givenScalarFieldsOnly_roundTrips() {
        val decoded = encodeThenDecode(
            LiveNotificationBranding(companyName = "Acme", accentColor = Color.RED)
        )

        decoded.shouldNotBeNull()
        decoded.companyName shouldBeEqualTo "Acme"
        decoded.accentColor shouldBeEqualTo Color.RED
        decoded.smallIcon.shouldBeNull()
        decoded.logo.shouldBeNull()
    }

    @Test
    fun encodeDecode_givenSmallIcon_roundTripsAsResourceName() {
        val decoded = encodeThenDecode(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = Color.BLUE,
                smallIcon = realResId
            )
        )

        decoded.shouldNotBeNull()
        decoded.smallIcon shouldBeEqualTo realResId
    }

    @Test
    fun encodeDecode_givenDrawableLogo_roundTrips() {
        val decoded = encodeThenDecode(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = Color.BLUE,
                logo = LiveNotificationAsset.Drawable(realResId)
            )
        )

        decoded.shouldNotBeNull()
        decoded.logo shouldBeEqualTo LiveNotificationAsset.Drawable(realResId)
    }

    @Test
    fun encodeDecode_givenRemoteUrlLogo_roundTrips() {
        val decoded = encodeThenDecode(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = Color.BLUE,
                logo = LiveNotificationAsset.RemoteUrl("https://example.com/logo.png")
            )
        )

        decoded.shouldNotBeNull()
        decoded.logo shouldBeEqualTo LiveNotificationAsset.RemoteUrl("https://example.com/logo.png")
    }

    @Test
    fun encodeDecode_givenResourceUriLogo_roundTrips() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val decoded = encodeThenDecode(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = Color.BLUE,
                logo = LiveNotificationAsset.Resource(uri)
            )
        )

        decoded.shouldNotBeNull()
        decoded.logo shouldBeEqualTo LiveNotificationAsset.Resource(uri)
    }

    @Test
    fun encode_givenBytesLogo_dropsLogoAndKeepsEverythingElse() {
        // Raw bytes are unbounded; SharedPreferences is the wrong home for an image payload. The
        // cold render loses the logo but keeps the rest of the branding.
        val decoded = encodeThenDecode(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = Color.GREEN,
                smallIcon = realResId,
                logo = LiveNotificationAsset.Bytes(byteArrayOf(1, 2, 3))
            )
        )

        decoded.shouldNotBeNull()
        decoded.logo.shouldBeNull()
        decoded.companyName shouldBeEqualTo "Acme"
        decoded.accentColor shouldBeEqualTo Color.GREEN
        decoded.smallIcon shouldBeEqualTo realResId
    }

    @Test
    fun decode_givenResourceTheAppNoLongerShips_yieldsNullAssetNotCrash() {
        // Simulates a drawable renamed or removed by an app update after branding was persisted.
        val json = JSONObject()
            .put("companyName", "Acme")
            .put("accentColor", Color.RED)
            .put(
                "smallIcon",
                JSONObject().put("resType", "drawable").put("resEntry", "cio_no_such_drawable")
            )
            .put(
                "logo",
                JSONObject()
                    .put("kind", "drawable")
                    .put("resType", "drawable")
                    .put("resEntry", "cio_no_such_drawable")
            )
            .toString()

        val decoded = LiveNotificationBrandingSerializer.decode(contextMock, json)

        decoded.shouldNotBeNull()
        decoded.smallIcon.shouldBeNull()
        decoded.logo.shouldBeNull()
        decoded.companyName shouldBeEqualTo "Acme"
    }

    @Test
    fun decode_givenUnknownLogoKind_ignoresLogo() {
        val json = JSONObject()
            .put("companyName", "Acme")
            .put("accentColor", Color.RED)
            .put("logo", JSONObject().put("kind", "something-a-newer-sdk-wrote"))
            .toString()

        val decoded = LiveNotificationBrandingSerializer.decode(contextMock, json)

        decoded.shouldNotBeNull()
        decoded.logo.shouldBeNull()
    }

    @Test
    fun decode_givenMalformedOrIncompleteJson_returnsNullWithoutThrowing() {
        LiveNotificationBrandingSerializer.decode(contextMock, "not json").shouldBeNull()
        LiveNotificationBrandingSerializer.decode(contextMock, "").shouldBeNull()
        // accentColor is required; a truncated entry must degrade to unbranded, not throw.
        LiveNotificationBrandingSerializer.decode(contextMock, """{"companyName":"Acme"}""").shouldBeNull()
    }
}

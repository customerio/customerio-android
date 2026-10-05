package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import io.customer.sdk.data.model.Region
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class CioApiKeyTest : JUnit5Test() {

    @Test
    fun isSecretKey_givenSecretKey_expectTrue() {
        CioApiKey.isSecretKey(SECRET_US_KEY).shouldBeTrue()
        CioApiKey.isPublicKey(SECRET_US_KEY).shouldBeFalse()
    }

    @Test
    fun isPublicKey_givenPublicKey_expectTrue() {
        CioApiKey.isPublicKey(PUBLIC_EU_KEY).shouldBeTrue()
        CioApiKey.isSecretKey(PUBLIC_EU_KEY).shouldBeFalse()
    }

    @Test
    fun isPublicKey_givenLegacyKey_expectFalse() {
        CioApiKey.isPublicKey(LEGACY_KEY).shouldBeFalse()
        CioApiKey.isSecretKey(LEGACY_KEY).shouldBeFalse()
    }

    @Test
    fun isPublicKey_givenUnknownRegionPrefix_expectFalse() {
        CioApiKey.isPublicKey("wk_ap_$KEY_BODY").shouldBeFalse()
        CioApiKey.isSecretKey("ak_ap_$KEY_BODY").shouldBeFalse()
    }

    @Test
    fun region_givenPrefixedKeys_expectRegionFromPrefix() {
        CioApiKey.region(PUBLIC_EU_KEY) shouldBe Region.EU
        CioApiKey.region(PUBLIC_US_KEY) shouldBe Region.US
        CioApiKey.region(SECRET_US_KEY) shouldBe Region.US
    }

    @Test
    fun region_givenLegacyKey_expectNull() {
        CioApiKey.region(LEGACY_KEY).shouldBeNull()
    }

    companion object {
        private const val KEY_BODY = "0123456789abcdefABCDEF0123456789_a1B2c3"
        const val PUBLIC_US_KEY = "wk_us_$KEY_BODY"
        const val PUBLIC_EU_KEY = "wk_eu_$KEY_BODY"
        const val SECRET_US_KEY = "ak_us_$KEY_BODY"
        const val LEGACY_KEY = "4f3b2a1c0d9e8f7a6b5c"
    }
}

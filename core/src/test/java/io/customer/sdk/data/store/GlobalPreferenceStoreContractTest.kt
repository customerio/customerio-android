// Contract: cio-sdk-tools `verify push` reads this prefs file and these keys straight from the device
// (its docs/verify-contract.md). Renaming the file, `device_token`, `config_settings` or `writeKey` breaks it.
package io.customer.sdk.data.store

import android.content.Context
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.data.model.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalPreferenceStoreContractTest : RobolectricTest() {

    private val store: GlobalPreferenceStore get() = GlobalPreferenceStoreImpl(contextMock)

    // Read the way the tool does: the raw prefs file, not the store
    private val rawPrefs
        get() = contextMock.getSharedPreferences("io.customer.sdk.${contextMock.packageName}", Context.MODE_PRIVATE)

    @Test
    fun saveDeviceToken_expectTokenUnderDeviceTokenKeyInPackagePrefsFile() {
        store.saveDeviceToken("fcm-token-123")

        rawPrefs.getString("device_token", null) shouldBeEqualTo "fcm-token-123"
    }

    @Test
    fun saveSettings_expectWriteKeyInConfigSettingsJson() {
        store.saveSettings(Settings(writeKey = "cdp-write-key", apiHost = "cdp.customer.io/v1"))

        val json = Json.parseToJsonElement(rawPrefs.getString("config_settings", null).orEmpty()).jsonObject
        json["writeKey"]?.jsonPrimitive?.content shouldBeEqualTo "cdp-write-key"
    }
}

package io.customer.sdk.data.store

import android.os.Bundle
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.Version
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClientTest : RobolectricTest() {
    private val defaultClientString: String = "Android Client/${Version.version}"

    private fun createMetadata(
        userAgent: String?,
        sdkVersion: String?
    ) = Bundle().apply {
        userAgent?.let { putString(Client.META_DATA_USER_AGENT, it) }
        sdkVersion?.let { putString(Client.META_DATA_SDK_VERSION, it) }
    }

    @Test
    fun fromManifest_givenValidMetaData_expectClientWithMetaData() {
        val metadata = createMetadata("ReactNative", "1.2.3")

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo "ReactNative Client/1.2.3"
    }

    @Test
    fun fromManifest_givenNullUserAgent_expectDefaultSourceUsed() {
        val metadata = createMetadata(null, "1.2.3")

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo defaultClientString
    }

    @Test
    fun fromManifest_givenEmptyUserAgent_expectDefaultSourceUsed() {
        val metadata = createMetadata("", "1.2.3")

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo defaultClientString
    }

    @Test
    fun fromManifest_givenNullSdkVersion_expectDefaultSdkVersionUsed() {
        val metadata = createMetadata("ReactNative", null)

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo defaultClientString
    }

    @Test
    fun fromManifest_givenEmptySdkVersion_expectDefaultSdkVersionUsed() {
        val metadata = createMetadata("ReactNative", "")

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo defaultClientString
    }

    @Test
    fun fromManifest_givenNullMetaData_expectDefaultValuesUsed() {
        val metadata = createMetadata(null, null)

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo defaultClientString
    }

    @Test
    fun fromManifest_givenEmptyMetaData_expectDefaultValuesUsed() {
        val metadata = createMetadata("", "")

        val client = Client.fromMetadata(metadata)

        client.toString() shouldBeEqualTo defaultClientString
    }
}

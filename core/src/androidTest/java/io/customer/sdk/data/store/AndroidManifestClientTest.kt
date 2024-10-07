package io.customer.sdk.data.store

import io.customer.commontest.core.AndroidTest
import io.customer.sdk.core.extensions.applicationMetaData
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class AndroidManifestClientTest : AndroidTest() {
    @Test
    fun fromManifest_givenTestMetaData_expectClientWithTestMetaData() {
        val client = Client.fromMetadata(application.applicationMetaData())

        client.toString() shouldBeEqualTo "TestUserAgent Client/1.3.5"
    }
}

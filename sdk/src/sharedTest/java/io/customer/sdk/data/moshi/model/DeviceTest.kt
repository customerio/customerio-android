package io.customer.sdk.data.moshi.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.data.request.Device
import io.customer.sdk.util.JsonAdapter
import java.util.Date
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTest : BaseTest() {

    private lateinit var adapter: JsonAdapter

    override fun setup() {
        super.setup()
        adapter = di.jsonAdapter
    }

    @Test
    fun parseDeviceJson_withInvalidLastUsedFormat_shouldStillDeserializeDevice() {
        val givenTimestamp = 1683394080L

        val json = """
        {
          "id": "123",
          "platform": "android",
          "lastUsed": $givenTimestamp,
          "attributes": {}
        }
        """.trimIndent()

        val device = jsonAdapter.fromJson<Device>(json)

        device shouldNotBe null
        device.lastUsed shouldBe null
    }

    @Test
    fun parseDeviceJson_givenValidLastUsedFormat_expectDeserializeDeviceCorrectly() {
        val givenTimestamp = 1683394080L

        // Convert Unix timestamp to Date object for comparison
        val expectedLastUsed = Date(givenTimestamp * 1000) // Multiply by 1000 to convert seconds to milliseconds

        val json = """
        {
          "id": "123",
          "platform": "android",
          "last_used": $givenTimestamp,
          "attributes": {}
        }
        """.trimIndent()

        val device = jsonAdapter.fromJson<Device>(json)

        device shouldNotBe null
        device.lastUsed shouldBeEqualTo expectedLastUsed
    }

    @Test
    fun serializeJson_verifyCorrectLastUsed() {
        val device = Device(
            token = "123",
            platform = "android",
            lastUsed = Date(),
            attributes = emptyMap()
        )

        val json = jsonAdapter.toJson(device)

        json shouldContain "\"last_used\""
    }
}

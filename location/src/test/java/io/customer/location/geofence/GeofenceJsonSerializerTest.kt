package io.customer.location.geofence

import io.customer.commontest.core.RobolectricTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldThrow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceJsonSerializerTest : RobolectricTest() {

    @Serializable
    private data class Sample(
        @SerialName("name") val name: String,
        @SerialName("count") val count: Int
    )

    private val jsonSerializer = GeofenceJsonSerializer()

    @Test
    fun encode_thenDecode_expectRoundTrip() {
        val original = Sample(name = "geo", count = 7)

        val encoded = jsonSerializer.encode(Sample.serializer(), original)
        val decoded = jsonSerializer.decode(Sample.serializer(), encoded)

        decoded shouldBeEqualTo original
    }

    @Test
    fun decode_givenInvalidJson_expectThrows() {
        invoking {
            jsonSerializer.decode(Sample.serializer(), "{ not json")
        } shouldThrow Exception::class
    }

    @Test
    fun decodeOrNull_givenInvalidJson_expectNull() {
        jsonSerializer.decodeOrNull(Sample.serializer(), "{ not json").shouldBeNull()
    }

    @Test
    fun decodeOrNull_givenValidJson_expectDecoded() {
        val original = Sample(name = "geo", count = 7)
        val encoded = jsonSerializer.encode(Sample.serializer(), original)

        val decoded = jsonSerializer.decodeOrNull(Sample.serializer(), encoded)

        decoded shouldBeEqualTo original
    }

    @Test
    fun decode_givenJsonWithUnknownFields_expectDecodedIgnoringUnknown() {
        val raw = """{"name":"geo","count":7,"future_field":"ignored"}"""

        jsonSerializer.decode(Sample.serializer(), raw) shouldBeEqualTo Sample("geo", 7)
    }
}

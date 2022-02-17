package io.customer.sdk.repositories

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.sdk.data.moshi.CustomerIOParser
import io.customer.sdk.data.moshi.CustomerIOParserImpl
import io.customer.sdk.repository.AttributesRepository
import io.customer.sdk.repository.MoshiAttributesRepositoryImp
import io.customer.sdk.utils.BaseTest
import org.amshove.kluent.shouldBeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

@RunWith(AndroidJUnit4::class)
internal class AttributesRepositoryTest : BaseTest() {

    private val parser: CustomerIOParser = CustomerIOParserImpl(di.moshi)
    lateinit var attributesRepository: AttributesRepository

    @Before
    override fun setup() {
        super.setup()

        attributesRepository = MoshiAttributesRepositoryImp(parser)
    }

    @Test
    fun `Verify string attributes are mapped correctly`() {
        val expected = mapOf("key" to "pair", "key2" to "pair2")

        val result = attributesRepository.mapToJson(mapOf("key" to "pair", "key2" to "pair2"))

        (result == expected).shouldBeTrue()
    }

    @Test
    fun `Verify int attributes are mapped correctly`() {

        val result = attributesRepository.mapToJson(mapOf("key" to 1, "key2" to 2))

        // JSON only has numbers. Not integers or doubles. And since numbers can have decimals they are always represented as doubles in Java.
        val expected = mapOf("key" to 1, "key2" to 2).mapValues { it.value.toBigDecimal() }

        (result == expected).shouldBeTrue()
    }

    @Test
    fun `Verify Long attributes are mapped correctly`() {
        val expected =
            sortedMapOf("key" to 1.0, "key2" to 2.0).mapValues { it.value.toBigDecimal() }

        val result =
            attributesRepository.mapToJson(mutableMapOf("key" to 1.0, "key2" to 2.0)).toSortedMap()

        (result == expected).shouldBeTrue()
    }

    @Test
    fun `Verify Date attributes are mapped correctly`() {

        val date = Date()
        val expected = mapOf("key" to date.getUnixTimestamp().toBigDecimal())

        // even if Date is sent, unix timestamp should be mapped
        val result = attributesRepository.mapToJson(mapOf("key" to date))

        (result == expected).shouldBeTrue()
    }

    enum class MockEnum {
        enum, ENUM
    }

    @Test
    fun `Verify Enum attributes are mapped correctly`() {

        val expected = mapOf("key" to MockEnum.ENUM.toString(), "key2" to MockEnum.enum.toString())

        // even if Date is sent, unix timestamp should be mapped
        val result =
            attributesRepository.mapToJson(mapOf("key" to MockEnum.ENUM, "key2" to MockEnum.enum))

        (result == expected).shouldBeTrue()
    }

    data class MockUnknownEvent(val data: Any)

    @Test
    fun `Verify unknown attributes gets ignored`() {

        val expected = mapOf<String, Any>()

        // even if Date is sent, unix timestamp should be mapped
        val result = attributesRepository.mapToJson(mapOf("key" to MockUnknownEvent(data = "test")))

        (result == expected).shouldBeTrue()
    }

    @Test
    fun `Verify unknown attributes gets ignored but others doesn't`() {

        val expected = mapOf("key2" to "valid")

        // even if Date is sent, unix timestamp should be mapped
        val result = attributesRepository.mapToJson(
            mapOf(
                "key" to MockUnknownEvent(data = "test"),
                "key2" to "valid"
            )
        )

        (result == expected).shouldBeTrue()
    }
}

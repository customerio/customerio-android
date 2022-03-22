package io.customer.sdk.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import com.squareup.moshi.JsonClass
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonAdapterTest : BaseTest() {

    private lateinit var jsonAdapter: JsonAdapter

    @Before
    override fun setup() {
        super.setup()

        jsonAdapter = JsonAdapter(di.moshi)
    }

    @JsonClass(generateAdapter = true)
    data class TestVo(
        val foo: String
    )

    // fromJson

    @Test
    fun fromJson_givenObjectString_expectGetObject() {
        val given =
            """
            {"foo": "bar"}
            """.trimIndent()

        val actual = jsonAdapter.fromJson<TestVo>(given)

        actual shouldBeEqualTo TestVo("bar")
    }

    @Test
    fun fromJson_givenListObjectsString_expectThrow() {
        val given: String = jsonAdapter.toJson(listOf(TestVo("bar")))

        invoking {
            jsonAdapter.fromJson(given)
        } shouldThrow AnyException
    }

    @Test
    fun fromJsonList_givenListString_expectGetListOfObjects() {
        val given =
            """
            [{"foo": "bar"}]
            """.trimIndent()

        val actual: List<TestVo> = jsonAdapter.fromJsonList(given)

        actual shouldBeEqualTo listOf(TestVo("bar"))
    }

    @Test
    fun fromJsonList_givenObjectString_expectThrow() {
        val given: String = jsonAdapter.toJson(TestVo("bar"))

        invoking {
            jsonAdapter.fromJsonList<TestVo>(given)
        } shouldThrow AnyException
    }

    // toJson

    @Test
    fun toJson_givenObject_expectGetObjectString() {
        val expected =
            """
            {"foo":"bar"}
            """.trimIndent()

        val actual = jsonAdapter.toJson(TestVo("bar"))

        actual shouldBeEqualTo expected
    }

    @Test
    fun toJson_givenListOfObjects_expectGetListOfObjectString() {
        val expected =
            """
            [{"foo":"bar"}]
            """.trimIndent()

        val actual = jsonAdapter.toJson(listOf(TestVo("bar")))

        actual shouldBeEqualTo expected
    }
}

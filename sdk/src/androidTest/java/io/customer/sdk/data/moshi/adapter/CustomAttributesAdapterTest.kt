package io.customer.sdk.data.moshi.adapter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.utils.TestCustomAttributesDataSet
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomAttributesAdapterTest : BaseTest() {

    private lateinit var adapter: CustomAttributesAdapter

    @Before
    override fun setup() {
        super.setup()

        adapter = CustomAttributesAdapter(di.moshi)
    }

    @Test
    fun toJson_givenAllKindsOfCustomAttributes_expectGetJsonString() {
        val testDataSet = TestCustomAttributesDataSet.values()
        testDataSet.isEmpty().shouldBeFalse()

        testDataSet.forEach { dataSet ->
            val given = dataSet.attributes
            val expected = dataSet.expectedJsonString

            val actual = adapter.toJson(given)

            actual shouldBeEqualTo expected
        }
    }

    @Test
    fun fromJson_givenAllKindsOfCustomAttributes_expectGetObject() {
        val testDataSet = TestCustomAttributesDataSet.values()
        testDataSet.isEmpty().shouldBeFalse()

        testDataSet.forEach { dataSet ->
            val given = dataSet.expectedJsonString
            val expected = dataSet.expectedAttributes

            val actual = adapter.fromJson(given)

            actual shouldBeEqualTo expected
        }
    }
}

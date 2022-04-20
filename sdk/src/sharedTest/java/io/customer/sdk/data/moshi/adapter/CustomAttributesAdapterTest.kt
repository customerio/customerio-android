package io.customer.sdk.data.moshi.adapter

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.data.model.CustomAttributes
import org.amshove.kluent.shouldBeEqualTo
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
    fun foo_given_expect() {
        val given: CustomAttributes = mapOf(
            "name" to "Dana",
            "age" to 100,
            "favorites" to mapOf(
                "movie" to "Star Wars"
            )
        )
        val expected = """
            {"name":"Dana","age":100,"favorites":{"movie":"Star Wars"}}
        """.trimIndent()

        val actual = adapter.toJson(given)

        actual shouldBeEqualTo expected
    }
}

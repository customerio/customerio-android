package io.customer.base.extensions

import io.customer.base.extenstions.containsAny
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

class CollectionExtensionsTest {

    // containsAny

    @Test
    fun containsAny_givenEmptyCollection_expectFalse() {
        val given: List<String> = emptyList()

        given.containsAny(listOf("foo")).shouldBeFalse()
    }

    @Test
    fun containsAny_givenCollectionWithNoItemsInCommon_expectFalse() {
        val given: List<String> = listOf("foo")

        given.containsAny(listOf("bar")).shouldBeFalse()
    }

    @Test
    fun containsAny_givenSimilarItems_expectTrue() {
        val given: List<String> = listOf("foo")

        given.containsAny(listOf("bar", "foo")).shouldBeTrue()
    }
}

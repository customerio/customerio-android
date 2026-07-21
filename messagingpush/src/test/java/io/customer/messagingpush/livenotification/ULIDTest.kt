package io.customer.messagingpush.livenotification

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeIn
import org.amshove.kluent.shouldBeLessThan
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

internal class ULIDTest {
    private val crockfordAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()

    @Test
    fun generate_givenAnyTimestamp_isExactly26Characters() {
        repeat(1000) { ULID.generate().length shouldBeEqualTo 26 }
    }

    @Test
    fun generate_givenAnyTimestamp_usesOnlyCrockfordAlphabet() {
        repeat(1000) {
            ULID.generate().all { it in crockfordAlphabet }.shouldBeTrue()
        }
    }

    @Test
    fun generate_givenAnyTimestamp_isUppercase() {
        repeat(1000) {
            val ulid = ULID.generate()
            ulid shouldBeEqualTo ulid.uppercase()
        }
    }

    @Test
    fun generate_givenAnyTimestamp_leadingCharacterIsAtMost7() {
        // 26 * 5 = 130 bits but the value is 128 bits, so the first character carries just
        // 3 significant timestamp bits and can never exceed '7'.
        repeat(1000) { ULID.generate().first() shouldBeIn "01234567".toList() }
    }

    @Test
    fun generate_givenCanonicalSpecTimestamp_encodesKnownPrefix() {
        // Canonical ULID spec example: 1469918176385 ms -> timestamp prefix "01ARYZ6S41"
        // (full spec ULID 01ARYZ6S41QJQECH4KPG6SEF3Y).
        ULID.generate(timestampMillis = 1_469_918_176_385L).take(10) shouldBeEqualTo "01ARYZ6S41"
    }

    @Test
    fun generate_givenEpoch_encodesAllZeroPrefix() {
        ULID.generate(timestampMillis = 0L).take(10) shouldBeEqualTo "0000000000"
    }

    @Test
    fun generate_givenManyIterations_producesUniqueValues() {
        val seen = HashSet<String>()
        repeat(10_000) { seen.add(ULID.generate()).shouldBeTrue() }
    }

    @Test
    fun generate_givenSameTimestamp_randomnessDiffers() {
        val a = ULID.generate(timestampMillis = 1_700_000_000_000L)
        val b = ULID.generate(timestampMillis = 1_700_000_000_000L)
        a.take(10) shouldBeEqualTo b.take(10)
        (a.takeLast(16) != b.takeLast(16)).shouldBeTrue()
    }

    @Test
    fun generate_givenIncreasingTimestamps_sortsLexicographically() {
        val earlier = ULID.generate(timestampMillis = 1_000L)
        val middle = ULID.generate(timestampMillis = 1_700_000_000_000L)
        val later = ULID.generate(timestampMillis = 4_000_000_000_000L)
        earlier shouldBeLessThan middle
        middle shouldBeLessThan later
    }

    @Test
    fun timestampMillis_givenGeneratedULID_roundTripsValue() {
        val expected = 1_700_000_000_000L
        ULID.timestampMillis(ULID.generate(timestampMillis = expected)) shouldBeEqualTo expected
    }

    @Test
    fun timestampMillis_givenMalformedInput_returnsNull() {
        ULID.timestampMillis("TOOSHORT").shouldBeNull()
        // 'I' is excluded from the Crockford alphabet.
        ULID.timestampMillis("I" + "0".repeat(25)).shouldBeNull()
    }
}

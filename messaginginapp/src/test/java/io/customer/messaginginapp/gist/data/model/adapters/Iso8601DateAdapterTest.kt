package io.customer.messaginginapp.gist.data.model.adapters

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.StringReader
import java.io.StringWriter
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class Iso8601DateAdapterTest : JUnitTest() {
    private val adapter = Iso8601DateAdapter()

    @Test
    fun testRead_givenValidDateWithMilliseconds_thenReturnsDate() {
        val dateString = "2026-01-27T12:30:45.123Z"
        val jsonReader = createJsonReader("\"$dateString\"")

        val result = adapter.read(jsonReader)

        result.shouldNotBeNull()
        val calendar = utcCalendar(result)
        calendar.get(Calendar.YEAR).shouldBeEqualTo(2026)
        calendar.get(Calendar.MONTH).shouldBeEqualTo(Calendar.JANUARY)
        calendar.get(Calendar.DAY_OF_MONTH).shouldBeEqualTo(27)
        calendar.get(Calendar.HOUR_OF_DAY).shouldBeEqualTo(12)
        calendar.get(Calendar.MINUTE).shouldBeEqualTo(30)
        calendar.get(Calendar.SECOND).shouldBeEqualTo(45)
        calendar.get(Calendar.MILLISECOND).shouldBeEqualTo(123)
    }

    @Test
    fun testRead_givenValidDateWithMicroseconds_thenTruncatesToMilliseconds() {
        // Server sends microseconds (6 digits), but Java Date only supports milliseconds
        val dateString = "2026-01-27T12:30:45.123456Z"
        val jsonReader = createJsonReader("\"$dateString\"")

        val result = adapter.read(jsonReader)

        result.shouldNotBeNull()
        val calendar = utcCalendar(result)
        calendar.get(Calendar.YEAR).shouldBeEqualTo(2026)
        calendar.get(Calendar.MONTH).shouldBeEqualTo(Calendar.JANUARY)
        calendar.get(Calendar.DAY_OF_MONTH).shouldBeEqualTo(27)
        calendar.get(Calendar.HOUR_OF_DAY).shouldBeEqualTo(12)
        calendar.get(Calendar.MINUTE).shouldBeEqualTo(30)
        calendar.get(Calendar.SECOND).shouldBeEqualTo(45)
        // Microseconds .123456 truncated to milliseconds .123
        calendar.get(Calendar.MILLISECOND).shouldBeEqualTo(123)
    }

    @Test
    fun testRead_givenValidDateWithSingleDigitFractionalSecond_thenPadsToMilliseconds() {
        // Per ISO 8601, .1 = 0.1 seconds = 100 milliseconds
        val dateString = "2026-01-27T12:30:45.1Z"
        val jsonReader = createJsonReader("\"$dateString\"")

        val result = adapter.read(jsonReader)

        result.shouldNotBeNull()
        val calendar = utcCalendar(result)
        calendar.get(Calendar.YEAR).shouldBeEqualTo(2026)
        calendar.get(Calendar.MONTH).shouldBeEqualTo(Calendar.JANUARY)
        calendar.get(Calendar.DAY_OF_MONTH).shouldBeEqualTo(27)
        calendar.get(Calendar.HOUR_OF_DAY).shouldBeEqualTo(12)
        calendar.get(Calendar.MINUTE).shouldBeEqualTo(30)
        calendar.get(Calendar.SECOND).shouldBeEqualTo(45)
        // .1 is padded to .100, representing 100 milliseconds
        calendar.get(Calendar.MILLISECOND).shouldBeEqualTo(100)
    }

    @Test
    fun testRead_givenValidDateWithTwoDigitFractionalSecond_thenPadsToMilliseconds() {
        // Per ISO 8601, .12 = 0.12 seconds = 120 milliseconds
        val dateString = "2026-01-27T12:30:45.12Z"
        val jsonReader = createJsonReader("\"$dateString\"")

        val result = adapter.read(jsonReader)

        result.shouldNotBeNull()
        val calendar = utcCalendar(result)
        calendar.get(Calendar.YEAR).shouldBeEqualTo(2026)
        calendar.get(Calendar.MONTH).shouldBeEqualTo(Calendar.JANUARY)
        calendar.get(Calendar.DAY_OF_MONTH).shouldBeEqualTo(27)
        calendar.get(Calendar.HOUR_OF_DAY).shouldBeEqualTo(12)
        calendar.get(Calendar.MINUTE).shouldBeEqualTo(30)
        calendar.get(Calendar.SECOND).shouldBeEqualTo(45)
        // .12 is padded to .120, representing 120 milliseconds
        calendar.get(Calendar.MILLISECOND).shouldBeEqualTo(120)
    }

    @Test
    fun testRead_givenValidDateWithoutMilliseconds_thenReturnsDate() {
        val dateString = "2026-01-27T12:30:45Z"
        val jsonReader = createJsonReader("\"$dateString\"")

        val result = adapter.read(jsonReader)

        result.shouldNotBeNull()
        val calendar = utcCalendar(result)
        calendar.get(Calendar.YEAR).shouldBeEqualTo(2026)
        calendar.get(Calendar.MONTH).shouldBeEqualTo(Calendar.JANUARY)
        calendar.get(Calendar.DAY_OF_MONTH).shouldBeEqualTo(27)
        calendar.get(Calendar.HOUR_OF_DAY).shouldBeEqualTo(12)
        calendar.get(Calendar.MINUTE).shouldBeEqualTo(30)
        calendar.get(Calendar.SECOND).shouldBeEqualTo(45)
    }

    @Test
    fun testRead_givenNullToken_thenReturnsNull() {
        val jsonReader = mockk<JsonReader>(relaxed = true)
        every { jsonReader.peek() } returns JsonToken.NULL

        val result = adapter.read(jsonReader)

        result.shouldBeNull()
        verify { jsonReader.nextNull() }
    }

    @Test
    fun testRead_givenBlankString_thenReturnsNull() {
        val jsonReader = createJsonReader("\"\"")

        val result = adapter.read(jsonReader)

        result.shouldBeNull()
    }

    @Test
    fun testRead_givenWhitespaceOnlyString_thenReturnsNull() {
        val jsonReader = createJsonReader("\"   \"")

        val result = adapter.read(jsonReader)

        result.shouldBeNull()
    }

    @Test
    fun testRead_givenInvalidDateFormat_thenReturnsNull() {
        val jsonReader = createJsonReader("\"not-a-date\"")

        val result = adapter.read(jsonReader)

        result.shouldBeNull()
    }

    @Test
    fun testRead_givenPartiallyValidDate_thenReturnsNull() {
        val jsonReader = createJsonReader("\"2026-01-27\"")

        val result = adapter.read(jsonReader)

        result.shouldBeNull()
    }

    @Test
    fun testRead_givenMalformedJson_thenReturnsNull() {
        val jsonReader = createJsonReader("\"2026-01-27T12:30:45\"") // Missing Z

        val result = adapter.read(jsonReader)

        result.shouldBeNull()
    }

    @Test
    fun testWrite_givenValidDate_thenWritesWithMilliseconds() {
        val date = utcDate(
            year = 2026,
            month = Calendar.JANUARY,
            day = 27,
            hour = 12,
            minute = 30,
            second = 45,
            millisecond = 123
        )
        val stringWriter = StringWriter()
        val jsonWriter = JsonWriter(stringWriter)

        adapter.write(jsonWriter, date)

        jsonWriter.close()
        val result = stringWriter.toString()
        result.shouldBeEqualTo("\"2026-01-27T12:30:45.123Z\"")
    }

    @Test
    fun testWrite_givenDateWithoutMilliseconds_thenWritesWithZeroMilliseconds() {
        val date = utcDate(
            year = 2026,
            month = Calendar.JANUARY,
            day = 27,
            hour = 12,
            minute = 30,
            second = 45,
            millisecond = 0
        )
        val stringWriter = StringWriter()
        val jsonWriter = JsonWriter(stringWriter)

        adapter.write(jsonWriter, date)

        jsonWriter.close()
        val result = stringWriter.toString()
        result.shouldBeEqualTo("\"2026-01-27T12:30:45.000Z\"")
    }

    @Test
    fun testWrite_givenNullDate_thenWritesNull() {
        val stringWriter = StringWriter()
        val jsonWriter = JsonWriter(stringWriter)

        adapter.write(jsonWriter, null)

        jsonWriter.close()
        val result = stringWriter.toString()
        result.shouldBeEqualTo("null")
    }

    @Test
    fun testWrite_givenDifferentDate_thenWritesCorrectly() {
        val date = utcDate(
            year = 2024,
            month = Calendar.DECEMBER,
            day = 31,
            hour = 23,
            minute = 59,
            second = 59,
            millisecond = 999
        )
        val stringWriter = StringWriter()
        val jsonWriter = JsonWriter(stringWriter)

        adapter.write(jsonWriter, date)

        jsonWriter.close()
        val result = stringWriter.toString()
        result.shouldBeEqualTo("\"2024-12-31T23:59:59.999Z\"")
    }

    @Test
    fun testWrite_givenEpochDate_thenWritesCorrectly() {
        val date = utcDate(
            year = 1970,
            month = Calendar.JANUARY,
            day = 1,
            hour = 0,
            minute = 0,
            second = 0,
            millisecond = 0
        )
        val stringWriter = StringWriter()
        val jsonWriter = JsonWriter(stringWriter)

        adapter.write(jsonWriter, date)

        jsonWriter.close()
        val result = stringWriter.toString()
        result.shouldBeEqualTo("\"1970-01-01T00:00:00.000Z\"")
    }

    private fun createJsonReader(json: String): JsonReader {
        return JsonReader(StringReader(json))
    }

    // Creates a Calendar instance in UTC timezone, optionally initialized with given date
    private fun utcCalendar(date: Date? = null): Calendar {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            date?.let { time = it }
        }
    }

    // Creates a Date in UTC timezone with specified date/time components
    private fun utcDate(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
        millisecond: Int = 0
    ): Date = utcCalendar().apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, millisecond)
    }.time
}

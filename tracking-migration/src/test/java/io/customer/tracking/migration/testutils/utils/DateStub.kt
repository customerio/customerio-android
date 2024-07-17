package io.customer.tracking.migration.testutils.utils

import io.customer.base.extenstions.getUnixTimestamp
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.Date

/**
 * Stub class for [Date] object to provide a fixed date and facilitate testing.
 * Call [mock] to mock the [Date] constructor and return the fixed date.
 * Call [unmock] to unmock the [Date] constructor and return original date.
 */
class DateStub(val date: Date = Date()) {
    val timeInSeconds: Long = date.time
    val timestamp: Long = date.getUnixTimestamp()
}

/**
 * Mock the [Date] constructor and return the fixed date.
 */
fun DateStub.mock() {
    mockkConstructor(Date::class)
    every { anyConstructed<Date>().time } returns timeInSeconds
}

/**
 * Unmock the [Date] constructor and return original date.
 */
fun DateStub.unmock() {
    unmockkConstructor(Date::class)
}

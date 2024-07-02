package io.customer.commontest.core

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * JUnit 5 test base class for all JUnit 5 tests in the project.
 * This class is responsible for basic setup and teardown of JUnit 5 test environment.
 * The class should only contain the common setup and teardown logic for all JUnit 5 tests.
 * Any additional setup or teardown logic should be implemented in respective child classes.
 *
 * Tests extending this class should make sure to import JUnit5 imports for test annotations.
 * e.g. import org.junit.jupiter.api.Test
 */
abstract class JUnit5Test : UnitTest() {
    final override val applicationMock: Application = mockk(relaxed = true)
    final override val contextMock: Context = applicationMock

    init {
        every { applicationMock.applicationContext } returns applicationMock
    }

    @BeforeEach
    fun setupTestEnvironment() {
        setup()
    }

    @AfterEach
    fun teardownTestEnvironment() {
        teardown()
    }
}

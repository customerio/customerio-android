package io.customer.commontest.core

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

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

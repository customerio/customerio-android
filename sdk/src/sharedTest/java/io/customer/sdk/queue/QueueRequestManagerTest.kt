package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.utils.BaseTest
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueRequestManagerTest : BaseTest() {

    private lateinit var manager: QueueRequestManagerImpl

    @Before
    override fun setup() {
        super.setup()

        manager = QueueRequestManagerImpl()
    }

    // queueRunRequestComplete

    @Test
    fun queueRunRequestComplete_expectChangeStatusIsRunningARequest() {
        manager.isRunningRequest = true

        manager.queueRunRequestComplete()
        val actual = manager.startRequest()

        actual.shouldBeFalse()
    }

    // startRequest

    @Test
    fun startRequest_givenNotRunningARequest_expectReturnFalse() {
        val actual = manager.startRequest()

        actual.shouldBeFalse()
    }

    @Test
    fun startRequest_givenRunningARequest_expectReturnTrue() {
        manager.isRunningRequest = true

        val actual = manager.startRequest()

        actual.shouldBeTrue()
    }
}

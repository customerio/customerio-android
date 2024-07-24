package io.customer.tracking.migration.queue

import io.customer.commontest.config.TestConfig
import io.customer.sdk.core.di.SDKComponent
import io.customer.tracking.migration.testutils.core.JUnitTest
import io.customer.tracking.migration.testutils.core.testConfiguration
import io.customer.tracking.migration.testutils.extensions.migrationSDKComponent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueTest : JUnitTest() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testCoroutineScope: CoroutineScope = TestScope(testDispatcher)

    private lateinit var queueRunRequestMock: QueueRunRequest
    private lateinit var queue: QueueImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    migrationSDKComponent {
                        overrideDependency<QueueRunRequest>(mockk())
                    }
                }
            }
        )

        val migrationSDKComponent = SDKComponent.migrationSDKComponent
        queueRunRequestMock = migrationSDKComponent.queueRunRequest
        queue = migrationSDKComponent.queue as QueueImpl

        coEvery { queueRunRequestMock.run() } coAnswers { delay(200L) }
    }

    @Test
    fun run_givenMultipleRunRequestsAtSameTime_expectQueueRunnerRunsOnce() = runTest(testDispatcher) {
        testCoroutineScope.launch { queue.run() }
        testCoroutineScope.launch { queue.run() }

        queue.isRunningRequest shouldBeEqualTo true
        coVerify(exactly = 1) { queueRunRequestMock.run() }
    }

    @Test
    fun run_givenMultipleRunRequestsAfterCompletion_expectQueueRunnerRunsMultipleTimes() = runTest(testDispatcher) {
        runBlocking { queue.run() }
        runBlocking { queue.run() }

        queue.isRunningRequest shouldBeEqualTo false
        coVerify(exactly = 2) { queueRunRequestMock.run() }
    }
}

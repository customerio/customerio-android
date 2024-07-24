package io.customer.tracking.migration.queue

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.random
import io.customer.sdk.core.di.SDKComponent
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.request.MigrationTask
import io.customer.tracking.migration.testutils.core.IntegrationTest
import io.customer.tracking.migration.testutils.core.testConfiguration
import io.customer.tracking.migration.testutils.extensions.migrationSDKComponent
import io.customer.tracking.migration.testutils.stubs.QueueStorageStub
import io.customer.tracking.migration.type.QueueTaskType
import io.customer.tracking.migration.util.JsonAdapter
import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueRunRequestTest : IntegrationTest() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val queueStorageStub: QueueStorageStub = QueueStorageStub()

    private lateinit var jsonAdapterMock: JsonAdapter
    private lateinit var migrationProcessorMock: MigrationProcessor
    private lateinit var queueQueryRunnerSpy: QueueQueryRunner
    private lateinit var queueRunnerSpy: QueueRunner
    private lateinit var queueRunRequest: QueueRunRequest
    private lateinit var queueStorageSpy: QueueStorage

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    migrationSDKComponent {
                        overrideDependency<CoroutineScope>(TestScope(testDispatcher))
                        overrideDependency<JsonAdapter>(mockk())
                        overrideDependency<QueueStorage>(spyk(queueStorageStub))
                        overrideDependency<QueueQueryRunner>(spyk(QueueQueryRunnerImpl(logger = logger)))
                        overrideDependency<QueueRunner>(
                            spyk(
                                QueueRunnerImpl(
                                    jsonAdapter = jsonAdapter,
                                    logger = logger,
                                    migrationProcessor = migrationProcessor
                                )
                            )
                        )
                    }
                }
            }
        )

        val migrationSDKComponent = SDKComponent.migrationSDKComponent
        jsonAdapterMock = migrationSDKComponent.jsonAdapter
        migrationProcessorMock = migrationSDKComponent.migrationProcessor
        queueQueryRunnerSpy = migrationSDKComponent.queueQueryRunner
        queueRunnerSpy = migrationSDKComponent.queueRunner
        queueRunRequest = migrationSDKComponent.queueRunRequest
        queueStorageSpy = migrationSDKComponent.queueStorage
    }

    override fun teardown() {
        queueStorageStub.reset()

        super.teardown()
    }

    @Test
    fun run_givenEmptyInventory_expectNoRunnerInteraction() = runTest(testDispatcher) {
        queueRunRequest.run()

        assertCalledOnce { queueStorageSpy.getInventory() }
        confirmVerified(queueStorageSpy)
        assertNoInteractions(queueRunnerSpy)
    }

    @Test
    fun run_givenSingleTaskInInventory_expectRunnerInteractedOnce() = runTest(testDispatcher) {
        jsonAdapterMock.mockParseTaskWithSuccess()
        migrationProcessorMock.mockProcessTaskWithSuccess()

        val inventory = queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        coVerify(exactly = 1) { queueRunnerSpy.runTask(any()) }
        assertCalledOnce { queueStorageSpy.delete(inventory.singleTaskId()) }
    }

    @Test
    fun run_givenMultipleTasksInInventory_expectRunnerInteractedMultipleTimes() = runTest(testDispatcher) {
        jsonAdapterMock.mockParseTaskWithSuccess()
        migrationProcessorMock.mockProcessTaskWithSuccess()

        queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        coVerify(exactly = 3) {
            queueRunnerSpy.runTask(any())
            queueStorageSpy.delete(any())
        }
    }

    @Test
    fun run_givenTaskParsingFailure_expectTaskDeleted() = runTest(testDispatcher) {
        jsonAdapterMock.mockParseTaskWithFailure()

        val inventory = queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        assertNoInteractions(migrationProcessorMock)
        assertCalledOnce { queueStorageSpy.delete(inventory.singleTaskId()) }
    }

    @Test
    fun run_givenTaskParsingThrows_expectTaskDeleted() = runTest(testDispatcher) {
        coEvery { jsonAdapterMock.parseMigrationTask(any()) } answers {
            throw RuntimeException(String.random)
        }

        val inventory = queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        assertNoInteractions(migrationProcessorMock)
        assertCalledOnce { queueStorageSpy.delete(inventory.singleTaskId()) }
    }

    @Test
    fun run_givenTaskMigrationSuccess_expectTaskDeleted() = runTest(testDispatcher) {
        jsonAdapterMock.mockParseTaskWithSuccess()
        migrationProcessorMock.mockProcessTaskWithSuccess()

        val inventory = queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        assertCalledOnce { queueStorageSpy.delete(inventory.singleTaskId()) }
    }

    @Test
    fun run_givenTaskMigrationFailure_expectTaskDeleted() = runTest(testDispatcher) {
        jsonAdapterMock.mockParseTaskWithSuccess()
        migrationProcessorMock.mockProcessTaskWithFailure()

        val inventory = queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        assertCalledOnce { queueStorageSpy.delete(inventory.singleTaskId()) }
    }

    @Test
    fun run_givenTaskMigrationThrows_expectTaskDeletedWithoutCrash() = runTest(testDispatcher) {
        coEvery { migrationProcessorMock.processTask(any<MigrationTask.TrackEvent>()) } answers {
            throw RuntimeException(String.random)
        }
        jsonAdapterMock.mockParseTaskWithSuccess()

        val inventory = queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        assertCalledOnce { queueStorageSpy.delete(inventory.singleTaskId()) }
    }

    @Test
    fun run_givenQueueStorageGetInventoryEmpty_expectQueueRunnerNeverRuns() = runTest(testDispatcher) {
        queueRunRequest.run()

        assertCalledOnce { queueStorageSpy.getInventory() }
        confirmVerified(queueStorageSpy, queueRunnerSpy, jsonAdapterMock, migrationProcessorMock)
    }

    @Test
    fun run_givenQueueStorageGetInventoryThrows_expectQueueFailsWithoutCrash() = runTest(testDispatcher) {
        coEvery { queueStorageSpy.getInventory() } answers {
            throw RuntimeException(String.random)
        }
        queueStorageStub.populateInventory { createTask(QueueTaskType.TrackEvent) }

        queueRunRequest.run()

        assertCalledOnce { queueStorageSpy.getInventory() }
        confirmVerified(queueStorageSpy, queueRunnerSpy, jsonAdapterMock, migrationProcessorMock)
    }

    @Test
    fun run_givenQueueStorageGetReturnsNull_expectQueueContinueForNextTasks() = runTest(testDispatcher) {
        val givenTaskPersistedId = String.random
        coEvery { queueStorageSpy.get(givenTaskPersistedId) } returns null
        queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent, taskPersistedId = givenTaskPersistedId)
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        coVerify(exactly = 2) { queueRunnerSpy.runTask(any()) }
        coVerify(exactly = 3) { queueStorageSpy.delete(any()) }
    }

    @Test
    fun run_givenQueueStorageGetThrows_expectQueueContinueForNextTasks() = runTest(testDispatcher) {
        val givenTaskPersistedId = String.random
        coEvery { queueStorageSpy.get(givenTaskPersistedId) } answers {
            throw RuntimeException(String.random)
        }
        queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent, taskPersistedId = givenTaskPersistedId)
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        coVerify(exactly = 2) {
            queueRunnerSpy.runTask(any())
            queueStorageSpy.delete(any())
        }
    }

    @Test
    fun run_givenQueueStorageDeleteThrows_expectQueueContinueForNextTasks() = runTest(testDispatcher) {
        val givenTaskPersistedId = String.random
        coEvery { queueStorageSpy.delete(givenTaskPersistedId) } answers {
            throw RuntimeException(String.random)
        }
        queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent, taskPersistedId = givenTaskPersistedId)
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        coVerify(exactly = 3) {
            queueRunnerSpy.runTask(any())
            queueStorageSpy.delete(any())
        }
    }

    @Test
    fun run_givenQueueQueryRunnerGetNextTaskNull_expectSkipExecutionForGivenTask() = runTest(testDispatcher) {
        every { queueQueryRunnerSpy.getNextTask(any()) } answers {
            val inventory = firstArg<List<QueueTaskMetadata>>()
            if (inventory.size == 2) {
                null
            } else {
                callOriginal()
            }
        }

        queueStorageStub.populateInventory {
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent)
            createTask(QueueTaskType.TrackEvent)
        }

        queueRunRequest.run()

        coVerify(exactly = 1) {
            queueRunnerSpy.runTask(any())
            queueStorageSpy.delete(any())
        }
    }
}

// Helper extension functions to reduce boilerplate

private fun QueueInventory.singleTaskId(): String {
    return shouldHaveSingleItem().taskPersistedId.shouldNotBeNull()
}

private fun MigrationProcessor.mockProcessTaskWithSuccess(
    matcher: MockKMatcherScope.() -> MigrationTask = { any<MigrationTask.TrackEvent>() }
) = coEvery {
    processTask(matcher())
} returns Result.success(Unit)

private fun MigrationProcessor.mockProcessTaskWithFailure(
    matcher: MockKMatcherScope.() -> MigrationTask = { any<MigrationTask.TrackEvent>() }
) = coEvery {
    processTask(matcher())
} returns Result.failure(RuntimeException("Migration task processing failed for testing"))

private fun JsonAdapter.mockParseTaskWithSuccess(
    matcher: MockKMatcherScope.() -> JSONObject = { any() },
    returnValue: MigrationTask = mockk<MigrationTask.TrackEvent>()
) = coEvery {
    parseMigrationTask(matcher())
} returns Result.success(returnValue)

private fun JsonAdapter.mockParseTaskWithFailure(
    matcher: MockKMatcherScope.() -> JSONObject = { any() }
) = coEvery {
    parseMigrationTask(matcher())
} returns Result.failure(RuntimeException("Migration task parsing failed for testing"))

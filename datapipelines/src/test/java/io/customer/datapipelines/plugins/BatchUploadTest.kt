package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Connection
import com.segment.analytics.kotlin.core.HTTPClient
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.utilities.StorageImpl
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.core.util.Clock
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.junit.jupiter.api.Test

class BatchUploadTest : JUnitTest() {
    private lateinit var storage: StorageImpl
    private val uploadedBodies = mutableListOf<String>()
    private var uploadAttempts = 0
    private var now = 1_000L

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk {
                        overrideDependency<Clock>(mockk { every { elapsedRealtime() } answers { now } })
                    }
                }
                sdkConfig {
                    autoAddCustomerIODestination(true)
                }
            }
        )
        storage = analytics.storage as StorageImpl
    }

    private fun failToConnect() {
        // What a DNS blocker that resolves the host to 0.0.0.0 looks like to the pipeline
        every { anyConstructed<HTTPClient>().upload(any()) } answers {
            uploadAttempts++
            throw IOException("Connection failed", ConnectException("Failed to connect to cdp.customer.io/0.0.0.0:443"))
        }
    }

    private fun respondWith(responseCode: Int) {
        every { anyConstructed<HTTPClient>().upload(any()) } answers {
            uploadAttempts++
            val body = ByteArrayOutputStream()
            object : Connection(mockk<HttpURLConnection>(relaxed = true), null, body) {
                override fun close() {
                    if (responseCode >= 300) throw httpException(responseCode)
                    uploadedBodies += body.toString()
                }
            }
        }
    }

    // Segment's HTTPException is internal, so build it the way its post connection does
    private fun httpException(code: Int): IOException {
        val type = Class.forName("com.segment.analytics.kotlin.core.HTTPException")
        val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 4 }
        check(constructor != null) { "Segment's HTTPException constructor changed" }
        constructor.isAccessible = true
        return constructor.newInstance(code, "status $code", null, mutableMapOf<String, MutableList<String>>()) as IOException
    }

    private suspend fun queuedFileCount(): Int {
        storage.rollover()
        return storage.read(Storage.Constants.Events)?.split(',')?.count { it.isNotBlank() } ?: 0
    }

    @Test
    fun flush_givenUploadsSucceed_expectEveryBatchUploadedOnItsFlush() = runTest {
        respondWith(200)

        repeat(3) { i ->
            sdkInstance.track("event-$i")
            analytics.flush()
        }

        uploadAttempts shouldBeEqualTo 3
        uploadedBodies.size shouldBeEqualTo 3
        uploadedBodies.forEachIndexed { i, body -> body.contains("\"event-$i\"") shouldBeEqualTo true }
        queuedFileCount() shouldBeEqualTo 0
    }

    @Test
    fun flush_givenServerRejectsBatch_expectBatchDroppedAndNextFlushStillUploads() = runTest {
        respondWith(400)
        sdkInstance.track("rejected")
        analytics.flush()

        uploadAttempts shouldBeEqualTo 1
        queuedFileCount() shouldBeEqualTo 0

        respondWith(200)
        sdkInstance.track("accepted")
        analytics.flush()

        uploadAttempts shouldBeEqualTo 2
        uploadedBodies.single().contains("\"accepted\"") shouldBeEqualTo true
    }

    @Test
    fun flush_givenServerError_expectBatchKeptAndNotRetriedOnEveryFlush() = runTest {
        respondWith(503)
        repeat(3) { i ->
            sdkInstance.track("event-$i")
            analytics.flush()
        }

        queuedFileCount() shouldBeEqualTo 3
        uploadAttempts shouldBeEqualTo 1
    }

    @Test
    fun flush_givenHostUnreachable_expectFailedPassToStopRetryingEveryFile() = runTest {
        failToConnect()
        val flushes = 5
        repeat(flushes) { i ->
            sdkInstance.track("event-$i")
            analytics.flush()
        }

        // Nothing was delivered, so every batch must still be queued
        queuedFileCount() shouldBeEqualTo flushes
        // Retrying every queued file on every flush would be 1 + 2 + ... + 5 = 15 attempts
        uploadAttempts shouldBeLessOrEqualTo flushes
    }

    @Test
    fun flush_givenQueuedFilesAndHostUnreachable_expectIdleFlushesNotToRetryRightAway() = runTest {
        failToConnect()
        val queued = 5
        repeat(queued) { i ->
            sdkInstance.track("event-$i")
            analytics.flush()
        }
        uploadAttempts = 0

        // Periodic flushes with no new events, as the 30 s timer does in the background
        repeat(5) { analytics.flush() }

        queuedFileCount() shouldBeEqualTo queued
        // Retrying every queued file on every flush would be 5 x 5 = 25 attempts
        uploadAttempts shouldBeLessOrEqualTo 1
    }

    @Test
    fun flush_givenHostReachableAgainAfterBackoff_expectEveryQueuedBatchUploaded() = runTest {
        failToConnect()
        repeat(3) { i ->
            sdkInstance.track("event-$i")
            analytics.flush()
        }
        queuedFileCount() shouldBeEqualTo 3

        respondWith(200)
        now += 34_000 // 30 s backoff plus up to 10% jitter
        // First flush after the backoff sends one batch; once it goes out, the next sends the rest
        analytics.flush()
        analytics.flush()

        queuedFileCount() shouldBeEqualTo 0
        uploadedBodies.size shouldBeEqualTo 3
        (0..2).forEach { i -> uploadedBodies.any { it.contains("\"event-$i\"") } shouldBeEqualTo true }
    }
}

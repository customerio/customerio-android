package io.customer.datapipelines.migration

import android.content.Context
import com.segment.analytics.kotlin.android.AndroidStorageImpl
import com.segment.analytics.kotlin.android.utilities.AndroidKVS
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.utilities.FileEventStream
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sovran.kotlin.Store

@RunWith(RobolectricTestRunner::class)
class AnalyticsStorageMigrationTest : RobolectricTest() {

    private lateinit var migration: AnalyticsStorageMigration

    @Before
    fun setUp() {
        super.setup(testConfigurationDefault {})
        migration = AnalyticsStorageMigration(applicationMock, mockk<Logger>(relaxed = true))
    }

    override fun teardown() {
        listOf(LEGACY_KEY, PUBLIC_KEY, OTHER_LEGACY_KEY).forEach { key ->
            applicationMock.getSharedPreferences("analytics-android-$key", Context.MODE_PRIVATE).edit().clear().commit()
        }
        applicationMock.getSharedPreferences(AnalyticsStorageMigration.MIGRATION_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        eventsDirectory().listFiles()?.forEach { it.deleteRecursively() }

        super.teardown()
    }

    private fun eventsDirectory() = applicationMock.getDir("segment-disk-queue", Context.MODE_PRIVATE)

    // Uses Segment's own storage so the test reads and writes the same names the SDK does at runtime
    private fun segmentStorage(writeKey: String): Storage = AndroidStorageImpl(
        propertiesFile = AndroidKVS(applicationMock.getSharedPreferences("analytics-android-$writeKey", Context.MODE_PRIVATE)),
        eventStream = FileEventStream(eventsDirectory()),
        store = Store(),
        writeKey = writeKey,
        fileIndexKey = "segment.events.file.index.$writeKey",
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun givenStorageForKey(writeKey: String, finishedBatch: Boolean = false) = runBlocking {
        segmentStorage(writeKey).apply {
            write(Storage.Constants.AnonymousId, ANONYMOUS_ID)
            write(Storage.Constants.UserId, USER_ID)
            write(Storage.Constants.Settings, "{}")
            write(Storage.Constants.Events, """{"event":"first"}""")
            if (finishedBatch) {
                rollover()
                write(Storage.Constants.Events, """{"event":"second"}""")
            }
        }
    }

    @Test
    fun migrate_givenSwitchFromLegacyToPublicKey_expectIdentityAndQueuedEventsCarryOver() = runBlocking<Unit> {
        givenStorageForKey(LEGACY_KEY, finishedBatch = true)

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        val newStorage = segmentStorage(PUBLIC_KEY)
        newStorage.read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
        newStorage.read(Storage.Constants.UserId) shouldBeEqualTo USER_ID
        // Settings are per key, so the new key fetches its own
        newStorage.read(Storage.Constants.Settings).shouldBeNull()

        // New events append to the moved in-progress batch, and both batches are queued for upload
        newStorage.write(Storage.Constants.Events, """{"event":"third"}""")
        newStorage.rollover()
        val batches = newStorage.read(Storage.Constants.Events)!!.split(", ")
        batches.size shouldBeEqualTo 2
        batches.forEach { it shouldContain "$PUBLIC_KEY-" }
        batches.joinToString { newStorage.readAsStream(it)!!.bufferedReader().readText() }.let { content ->
            content shouldContain "first"
            content shouldContain "second"
            content shouldContain "third"
            // Finished batches upload with the key written in them
            content shouldContain "\"writeKey\":\"$PUBLIC_KEY\""
            content shouldNotContain LEGACY_KEY
        }
        eventsDirectory().list()!!.forEach { it shouldNotContain LEGACY_KEY }

        // Previous key storage is cleared
        segmentStorage(LEGACY_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
    }

    @Test
    fun migrate_givenSwitchBetweenLegacyKeys_expectNothingMoved() {
        givenStorageForKey(LEGACY_KEY)

        migration.migrate(previousKey = LEGACY_KEY, newKey = OTHER_LEGACY_KEY)

        segmentStorage(OTHER_LEGACY_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
        segmentStorage(LEGACY_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
    }

    @Test
    fun migrate_givenSameKey_expectNothingMoved() {
        givenStorageForKey(PUBLIC_KEY)

        migration.migrate(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
    }

    @Test
    fun migrate_givenNoPreviousKey_expectNothingMoved() {
        migration.migrate(previousKey = null, newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
        eventsDirectory().list()!!.toList().shouldBeEmpty()
    }

    @Test
    fun migrate_givenPublicKeyAlreadyHasStorage_expectExistingStorageKept() = runBlocking<Unit> {
        givenStorageForKey(LEGACY_KEY)
        segmentStorage(PUBLIC_KEY).write(Storage.Constants.AnonymousId, "existing-anonymous-id")

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo "existing-anonymous-id"
        segmentStorage(LEGACY_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
    }

    @Test
    fun migrate_givenEventFileFailsToMove_expectRetriedOnNextLaunch() = runBlocking<Unit> {
        givenStorageForKey(LEGACY_KEY, finishedBatch = true)
        val finishedBatch = eventsDirectory().list()!!.first { !it.endsWith(".tmp") }
        // A non-empty directory at the target name makes the rename fail
        val blocker = java.io.File(eventsDirectory(), finishedBatch.replace(LEGACY_KEY, PUBLIC_KEY)).apply {
            mkdirs()
            java.io.File(this, "file").writeText("")
        }

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        eventsDirectory().list()!!.toList() shouldContain finishedBatch
        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID

        blocker.deleteRecursively()
        migration.migrate(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)

        eventsDirectory().list()!!.forEach { it shouldNotContain LEGACY_KEY }
    }

    companion object {
        private const val LEGACY_KEY = "4f3b2a1c0d9e8f7a6b5c"
        private const val OTHER_LEGACY_KEY = "9a8b7c6d5e4f3a2b1c0d"
        private const val PUBLIC_KEY = "wk_us_0123456789abcdefABCDEF0123456789_a1B2c3"
        private const val ANONYMOUS_ID = "anonymous-id"
        private const val USER_ID = "user-id"
    }
}

package io.customer.datapipelines.migration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.segment.analytics.kotlin.android.AndroidStorageImpl
import com.segment.analytics.kotlin.android.utilities.AndroidKVS
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.utilities.FileEventStream
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldEndWith
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotContain
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sovran.kotlin.Store

@RunWith(RobolectricTestRunner::class)
class AnalyticsStorageMigrationTest : RobolectricTest() {

    private lateinit var migration: AnalyticsStorageMigration
    private val mockLogger = mockk<Logger>(relaxed = true)

    @Before
    fun setUp() {
        super.setup(testConfigurationDefault {})
        migration = AnalyticsStorageMigration(applicationMock, mockLogger)
    }

    override fun teardown() {
        listOf(LEGACY_KEY, PUBLIC_KEY, OTHER_LEGACY_KEY, OTHER_PUBLIC_KEY).forEach { key ->
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

    // A non-empty directory where the rewrite is staged makes rewriting a finished batch fail
    private fun blockBatchRewrite() = java.io.File(eventsDirectory(), AnalyticsStorageMigration.REWRITE_FILE_NAME).apply {
        mkdirs()
        java.io.File(this, "file").writeText("")
    }

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
        val originalContents = java.io.File(eventsDirectory(), finishedBatch).readText()
        val blocker = blockBatchRewrite()

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        // The failed rewrite leaves the batch whole and in place
        eventsDirectory().list()!!.toList() shouldContain finishedBatch
        java.io.File(eventsDirectory(), finishedBatch).readText() shouldBeEqualTo originalContents
        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID

        blocker.deleteRecursively()
        migration.migrate(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)

        eventsDirectory().list()!!.forEach { it shouldNotContain LEGACY_KEY }
    }

    @Test
    fun migrate_givenMoveInterruptedAfterPrefsCopied_expectQueuedEventsMovedOnNextLaunch() = runBlocking<Unit> {
        givenStorageForKey(LEGACY_KEY, finishedBatch = true)
        // State left by a crash after the pending marker and new prefs were saved, before events moved
        applicationMock.getSharedPreferences(AnalyticsStorageMigration.MIGRATION_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("${AnalyticsStorageMigration.PENDING_EVENTS_PREFIX}$LEGACY_KEY", true).commit()
        segmentStorage(PUBLIC_KEY).write(Storage.Constants.AnonymousId, ANONYMOUS_ID)

        migration.migrate(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)

        eventsDirectory().list()!!.toList().shouldNotBeEmpty()
        eventsDirectory().list()!!.forEach { it shouldNotContain LEGACY_KEY }
    }

    @Test
    fun migrate_givenEventPropertyMatchesPreviousKey_expectOnlyBatchKeyRewritten() = runBlocking<Unit> {
        val eventWithKey = """{"event":"keyed","properties":{"writeKey":"$LEGACY_KEY"}}"""
        segmentStorage(LEGACY_KEY).apply {
            write(Storage.Constants.AnonymousId, ANONYMOUS_ID)
            write(Storage.Constants.Events, eventWithKey)
            rollover()
            // Left in the unfinished (.tmp) batch
            write(Storage.Constants.Events, eventWithKey)
        }

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        val files = eventsDirectory().listFiles()!!.toList()
        files.size shouldBeEqualTo 2
        files.forEach { file ->
            file.name shouldContain "$PUBLIC_KEY-"
            file.readText() shouldContain eventWithKey
        }
        val finished = files.first { !it.name.endsWith(".tmp") }.readText()
        finished shouldEndWith "\"writeKey\":\"$PUBLIC_KEY\"}"
    }

    @Test
    fun migrate_givenUnfinishedMoveThenSecondKeyChange_expectAllBatchesMoveToNewestKey() = runBlocking<Unit> {
        givenStorageForKey(LEGACY_KEY, finishedBatch = true)
        val finishedBatch = eventsDirectory().list()!!.first { !it.endsWith(".tmp") }
        val blocker = blockBatchRewrite()
        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)
        eventsDirectory().list()!!.toList() shouldContain finishedBatch
        blocker.deleteRecursively()

        migration.migrate(previousKey = PUBLIC_KEY, newKey = OTHER_PUBLIC_KEY)

        val newStorage = segmentStorage(OTHER_PUBLIC_KEY)
        newStorage.read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
        newStorage.write(Storage.Constants.Events, """{"event":"third"}""")
        newStorage.rollover()
        val files = eventsDirectory().listFiles()!!.toList()
        files.size shouldBeEqualTo 2
        files.forEach { file ->
            file.name shouldContain "$OTHER_PUBLIC_KEY-"
            file.readText() shouldEndWith "\"writeKey\":\"$OTHER_PUBLIC_KEY\"}"
        }
        files.joinToString { it.readText() }.let { content ->
            content shouldContain "first"
            content shouldContain "second"
            content shouldContain "third"
        }
    }

    @Test
    fun migrate_givenNewKeyAlreadyUsedBatchNumbers_expectMovedBatchesTakeFreeNumbers() = runBlocking<Unit> {
        // Leftover batches from a move that didn't finish
        givenStorageForKey(LEGACY_KEY, finishedBatch = true)
        applicationMock.getSharedPreferences(AnalyticsStorageMigration.MIGRATION_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("${AnalyticsStorageMigration.PENDING_EVENTS_PREFIX}$LEGACY_KEY", true).commit()
        // The new key already finished batch 1 and has batch 2 open
        java.io.File(eventsDirectory(), "$PUBLIC_KEY-1").writeText("finished by new key")
        java.io.File(eventsDirectory(), "$PUBLIC_KEY-2.tmp").writeText("open in new key")

        migration.migrate(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)

        java.io.File(eventsDirectory(), "$PUBLIC_KEY-1").readText() shouldBeEqualTo "finished by new key"
        java.io.File(eventsDirectory(), "$PUBLIC_KEY-2.tmp").readText() shouldBeEqualTo "open in new key"
        java.io.File(eventsDirectory(), "$PUBLIC_KEY-0").readText() shouldContain "first"
        java.io.File(eventsDirectory(), "$PUBLIC_KEY-3.tmp").readText() shouldContain "second"
        eventsDirectory().list()!!.forEach { it shouldNotContain LEGACY_KEY }
    }

    @Test
    fun migrate_givenNewPrefsFailToSave_expectPreviousStorageKeptUntilSaved() = runBlocking<Unit> {
        givenStorageForKey(LEGACY_KEY, finishedBatch = true)
        val newPrefsName = "analytics-android-$PUBLIC_KEY"
        val realNewPrefs = applicationMock.getSharedPreferences(newPrefsName, Context.MODE_PRIVATE)
        // Saving the copy fails, so nothing reaches the new key's prefs
        val failingNewPrefs = object : SharedPreferences by realNewPrefs {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by realNewPrefs.edit() {
                override fun commit(): Boolean = false
            }
        }
        val failingContext = object : ContextWrapper(applicationMock) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                if (name == newPrefsName) failingNewPrefs else super.getSharedPreferences(name, mode)
        }

        AnalyticsStorageMigration(failingContext, mockLogger).migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        segmentStorage(LEGACY_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
        // Events wait for their prefs to be saved
        eventsDirectory().list()!!.forEach { it shouldContain LEGACY_KEY }
        verify { mockLogger.error("Failed to save analytics storage for new public key, will retry on next launch", any(), any()) }

        // Next launch, the copy is saved, then the previous prefs are cleared and events move
        migration.migrate(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
        segmentStorage(LEGACY_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
        eventsDirectory().list()!!.forEach { it shouldNotContain LEGACY_KEY }
        applicationMock.getSharedPreferences(AnalyticsStorageMigration.MIGRATION_PREFS_NAME, Context.MODE_PRIVATE).all.shouldBeEmpty()
    }

    @Test
    fun migrate_givenPrefsOfEveryType_expectAllCopied() {
        legacyPrefs().edit()
            .putString("string", "value")
            .putInt("int", 1)
            .putLong("long", 2L)
            .putFloat("float", 3f)
            .putBoolean("boolean", true)
            .putStringSet("set", setOf("a", "b"))
            .commit()

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        val newPrefs = applicationMock.getSharedPreferences("analytics-android-$PUBLIC_KEY", Context.MODE_PRIVATE)
        newPrefs.getString("string", null) shouldBeEqualTo "value"
        newPrefs.getInt("int", 0) shouldBeEqualTo 1
        newPrefs.getLong("long", 0L) shouldBeEqualTo 2L
        newPrefs.getFloat("float", 0f) shouldBeEqualTo 3f
        newPrefs.getBoolean("boolean", false) shouldBeEqualTo true
        newPrefs.getStringSet("set", null) shouldBeEqualTo setOf("a", "b")
        legacyPrefs().all.shouldBeEmpty()
    }

    @Test
    fun migrate_givenEmptyPreviousKey_expectNothingMoved() {
        migration.migrate(previousKey = "", newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
    }

    @Test
    fun migrate_givenPreviousKeyHasNoStorage_expectNothingMoved() {
        java.io.File(eventsDirectory(), "$LEGACY_KEY-0").writeText("{}")

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
        eventsDirectory().list()!!.toList() shouldContain "$LEGACY_KEY-0"
    }

    @Test
    fun migrate_givenEventFileUnreadable_expectErrorLoggedAndFileKept() {
        givenStorageForKey(LEGACY_KEY)
        // A directory with the batch name can't be read as a file
        java.io.File(eventsDirectory(), "$LEGACY_KEY-9").mkdirs()

        migration.migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        eventsDirectory().list()!!.toList() shouldContain "$LEGACY_KEY-9"
        verify { mockLogger.error("Failed to move queued analytics events to new public key, will retry on next launch", any(), any()) }
    }

    @Test
    fun migrate_givenStorageThrows_expectErrorLogged() {
        val brokenContext = mockk<Context>()
        every { brokenContext.getSharedPreferences(any(), any()) } throws IllegalStateException("broken")

        AnalyticsStorageMigration(brokenContext, mockLogger).migrate(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        verify { mockLogger.error("Failed to move analytics storage to new public key: broken", any(), any()) }
    }

    private fun legacyPrefs() = applicationMock.getSharedPreferences("analytics-android-$LEGACY_KEY", Context.MODE_PRIVATE)

    companion object {
        private const val LEGACY_KEY = "4f3b2a1c0d9e8f7a6b5c"
        private const val OTHER_LEGACY_KEY = "9a8b7c6d5e4f3a2b1c0d"
        private const val PUBLIC_KEY = "pk_us_0123456789abcdefABCDEF0123456789_a1B2c3"
        private const val OTHER_PUBLIC_KEY = "pk_eu_9876543210abcdefABCDEF9876543210_c3B2a1"
        private const val ANONYMOUS_ID = "anonymous-id"
        private const val USER_ID = "user-id"
    }
}

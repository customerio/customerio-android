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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
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
    private val mockLogger = mockk<Logger>(relaxed = true)

    @Before
    fun setUp() {
        super.setup(testConfigurationDefault {})
        migration = AnalyticsStorageMigration(applicationMock, mockLogger)
    }

    override fun teardown() {
        listOf(LEGACY_KEY, PUBLIC_KEY, OTHER_LEGACY_KEY).forEach { key ->
            applicationMock.getSharedPreferences("analytics-android-$key", Context.MODE_PRIVATE).edit().clear().commit()
        }
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

    private fun givenIdentityForKey(writeKey: String) = runBlocking {
        segmentStorage(writeKey).apply {
            write(Storage.Constants.AnonymousId, ANONYMOUS_ID)
            write(Storage.Constants.UserId, USER_ID)
            write(Storage.Constants.Traits, TRAITS)
        }
    }

    private fun eventsFile(name: String) = File(eventsDirectory(), name)

    private fun moveUnfinishedEvents(previousKey: String, newKey: String, newStorage: Storage) = runBlocking {
        migration.moveUnfinishedEvents(
            previousKey = previousKey,
            newKey = newKey,
            scope = this,
            dispatcher = Dispatchers.Unconfined,
            storage = newStorage
        )
    }

    // Finishes the new key's batch and returns what it will upload
    private fun uploadedEvents(storage: Storage): String = runBlocking {
        storage.rollover()
        storage.read(Storage.Constants.Events).orEmpty().split(", ").filter { it.isNotEmpty() }
            .joinToString { storage.readAsStream(it)!!.bufferedReader().readText() }
    }

    @Test
    fun copyIdentity_givenSwitchToPublicKey_expectIdentityCopiedAndPreviousKept() {
        givenIdentityForKey(LEGACY_KEY)

        migration.copyIdentity(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        listOf(PUBLIC_KEY, LEGACY_KEY).forEach { key ->
            val storage = segmentStorage(key)
            storage.read(Storage.Constants.AnonymousId) shouldBeEqualTo ANONYMOUS_ID
            storage.read(Storage.Constants.UserId) shouldBeEqualTo USER_ID
            storage.read(Storage.Constants.Traits) shouldBeEqualTo TRAITS
        }
    }

    @Test
    fun copyIdentity_givenPublicKeyAlreadyHasIdentity_expectExistingIdentityKept() = runBlocking<Unit> {
        givenIdentityForKey(LEGACY_KEY)
        segmentStorage(PUBLIC_KEY).write(Storage.Constants.AnonymousId, "existing-anonymous-id")

        migration.copyIdentity(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo "existing-anonymous-id"
        segmentStorage(PUBLIC_KEY).read(Storage.Constants.UserId).shouldBeNull()
    }

    @Test
    fun moveUnfinishedEvents_givenFinishedBatches_expectFilesUntouched() {
        eventsFile("$LEGACY_KEY-0").writeText(FINISHED_BATCH)

        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY, newStorage = segmentStorage(PUBLIC_KEY))

        eventsDirectory().list()!!.toList() shouldBeEqualTo listOf("$LEGACY_KEY-0")
        eventsFile("$LEGACY_KEY-0").readText() shouldBeEqualTo FINISHED_BATCH
    }

    @Test
    fun moveUnfinishedEvents_givenUnfinishedBatch_expectEventsAddedAsWrittenAndBatchDeleted() {
        eventsFile("$LEGACY_KEY-1.tmp").writeText("""{"batch":[$FIRST_EVENT,$SECOND_EVENT""")
        val newStorage = segmentStorage(PUBLIC_KEY)

        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY, newStorage = newStorage)

        eventsFile("$LEGACY_KEY-1.tmp").exists().shouldBeFalse()
        uploadedEvents(newStorage).let { uploaded ->
            // Original messageId, timestamp and userId are kept
            uploaded shouldContain FIRST_EVENT
            uploaded shouldContain SECOND_EVENT
            uploaded shouldContain "\"writeKey\":\"$PUBLIC_KEY\""
        }
    }

    @Test
    fun moveUnfinishedEvents_givenCutOffLastEvent_expectItDropped() {
        eventsFile("$LEGACY_KEY-1.tmp").writeText("""{"batch":[$FIRST_EVENT,{"messageId":"cut-off","properties":{"no""")
        val newStorage = segmentStorage(PUBLIC_KEY)

        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY, newStorage = newStorage)

        eventsFile("$LEGACY_KEY-1.tmp").exists().shouldBeFalse()
        uploadedEvents(newStorage).let { uploaded ->
            uploaded shouldContain FIRST_EVENT
            uploaded shouldNotContain "cut-off"
        }
    }

    @Test
    fun migrate_givenSameKey_expectNothingChanged() {
        givenIdentityForKey(LEGACY_KEY)
        eventsFile("$PUBLIC_KEY-1.tmp").writeText("""{"batch":[$FIRST_EVENT""")

        migration.copyIdentity(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY)
        moveUnfinishedEvents(previousKey = PUBLIC_KEY, newKey = PUBLIC_KEY, newStorage = segmentStorage(PUBLIC_KEY))

        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
        eventsFile("$PUBLIC_KEY-1.tmp").readText() shouldBeEqualTo """{"batch":[$FIRST_EVENT"""
    }

    @Test
    fun migrate_givenSwitchBetweenLegacyKeys_expectNothingChanged() {
        givenIdentityForKey(LEGACY_KEY)
        eventsFile("$LEGACY_KEY-1.tmp").writeText("""{"batch":[$FIRST_EVENT""")

        migration.copyIdentity(previousKey = LEGACY_KEY, newKey = OTHER_LEGACY_KEY)
        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = OTHER_LEGACY_KEY, newStorage = segmentStorage(OTHER_LEGACY_KEY))

        segmentStorage(OTHER_LEGACY_KEY).read(Storage.Constants.AnonymousId).shouldBeNull()
        eventsFile("$LEGACY_KEY-1.tmp").exists() shouldBeEqualTo true
    }

    companion object {
        private const val LEGACY_KEY = "4f3b2a1c0d9e8f7a6b5c"
        private const val OTHER_LEGACY_KEY = "9a8b7c6d5e4f3a2b1c0d"
        private const val PUBLIC_KEY = "pk_us_0123456789abcdefABCDEF0123456789_a1B2c3"
        private const val ANONYMOUS_ID = "anonymous-id"
        private const val USER_ID = "user-id"
        private const val TRAITS = """{"email":"user@example.com"}"""
        private const val FINISHED_BATCH = """{"batch":[{"event":"done"}],"sentAt":"2026-10-19T00:00:00Z","writeKey":"$LEGACY_KEY"}"""

        // Braces and quotes inside strings must not split events
        private const val FIRST_EVENT =
            """{"messageId":"m-1","timestamp":"2026-10-19T00:00:01Z","userId":"u-1","type":"track","event":"first","properties":{"note":"a } \" {"}}"""
        private const val SECOND_EVENT =
            """{"messageId":"m-2","timestamp":"2026-10-19T00:00:02Z","userId":"u-1","type":"track","event":"second"}"""
    }
}

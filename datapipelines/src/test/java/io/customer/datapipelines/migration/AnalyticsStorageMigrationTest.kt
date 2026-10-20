package io.customer.datapipelines.migration

import android.app.Activity
import android.content.Context
import com.segment.analytics.kotlin.android.AndroidStorageImpl
import com.segment.analytics.kotlin.android.AndroidStorageProvider
import com.segment.analytics.kotlin.android.plugins.AndroidLifecyclePlugin
import com.segment.analytics.kotlin.android.utilities.AndroidKVS
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.Settings
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.utilities.FileEventStream
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.datapipelines.testutils.stubs.TestCoroutineConfiguration
import io.customer.datapipelines.testutils.utils.TestRunPlugin
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        listOf(LEGACY_KEY, PUBLIC_KEY, OTHER_LEGACY_KEY, SEGMENT_KEY).forEach { key ->
            applicationMock.getSharedPreferences("analytics-android-$key", Context.MODE_PRIVATE).edit().clear().commit()
            prefsFile(key).delete()
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

    private fun prefsFile(writeKey: String) =
        File(applicationMock.applicationInfo.dataDir, "shared_prefs/analytics-android-$writeKey.xml")

    // Storage as analytics leaves it for a key, with the settings it saved naming these destinations
    private fun givenStorageForKey(writeKey: String, destinations: List<String>?, lastUsed: Long) {
        val editor = applicationMock.getSharedPreferences("analytics-android-$writeKey", Context.MODE_PRIVATE).edit()
            .putString(Storage.Constants.AnonymousId.rawVal, "anonymous-$writeKey")
        destinations?.let {
            val settings = Settings(
                integrations = buildJsonObject {
                    destinations.forEach { destination ->
                        if (destination == SEGMENT_DESTINATION) {
                            put(destination, buildJsonObject { put("apiKey", writeKey) })
                        } else {
                            // Earlier SDK versions add their destination to the settings as `true`
                            put(destination, JsonPrimitive(true))
                        }
                    }
                }
            )
            editor.putString(Storage.Constants.Settings.rawVal, Json.encodeToString(Settings.serializer(), settings))
        }
        editor.commit()
        prefsFile(writeKey).setLastModified(lastUsed)
    }

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

    // Starts analytics for the key with Segment's own lifecycle plugin and returns the events it tracks on launch
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun lifecycleEventsOnLaunch(writeKey: String): List<TrackEvent> {
        val configuration = Configuration(writeKey = writeKey, application = applicationMock).apply {
            storageProvider = AndroidStorageProvider
            trackApplicationLifecycleEvents = true
            autoAddSegmentDestination = false
        }
        val analytics = object : Analytics(configuration, TestCoroutineConfiguration(UnconfinedTestDispatcher())) {}
        val lifecyclePlugin = AndroidLifecyclePlugin()
        analytics.add(lifecyclePlugin)
        val tracks = mutableListOf<TrackEvent>()
        analytics.add(TestRunPlugin { event -> (event as? TrackEvent)?.let(tracks::add) })

        lifecyclePlugin.onActivityCreated(mockk<Activity>(), null)

        return tracks
    }

    @Test
    fun previousKey_givenSavedKey_expectSavedKeyUsed() {
        givenStorageForKey(OTHER_LEGACY_KEY, destinations = listOf(CUSTOMER_IO_DESTINATION), lastUsed = 2_000_000L)

        migration.previousKey(savedKey = LEGACY_KEY, newKey = PUBLIC_KEY) shouldBeEqualTo LEGACY_KEY
    }

    @Test
    fun previousKey_givenSettingsNamingCustomerIODestination_expectThatKey() {
        // Before fetching, analytics also lists Segment's destination in this SDK's settings
        givenStorageForKey(LEGACY_KEY, destinations = listOf(SEGMENT_DESTINATION, CUSTOMER_IO_DESTINATION), lastUsed = 1_000_000L)
        givenStorageForKey(OTHER_LEGACY_KEY, destinations = null, lastUsed = 2_000_000L)

        val previousKey = migration.previousKey(savedKey = null, newKey = PUBLIC_KEY)
        migration.copyIdentity(previousKey = previousKey, newKey = PUBLIC_KEY)

        previousKey shouldBeEqualTo LEGACY_KEY
        segmentStorage(PUBLIC_KEY).read(Storage.Constants.AnonymousId) shouldBeEqualTo "anonymous-$LEGACY_KEY"
    }

    @Test
    fun previousKey_givenSeveralKeysNamingCustomerIODestination_expectMostRecentlyUsed() {
        givenStorageForKey(LEGACY_KEY, destinations = listOf(CUSTOMER_IO_DESTINATION), lastUsed = 1_000_000L)
        givenStorageForKey(OTHER_LEGACY_KEY, destinations = listOf(CUSTOMER_IO_DESTINATION), lastUsed = 3_000_000L)

        migration.previousKey(savedKey = null, newKey = PUBLIC_KEY) shouldBeEqualTo OTHER_LEGACY_KEY
    }

    @Test
    fun previousKey_givenNoSettingsAndOneOtherKey_expectThatKey() {
        givenStorageForKey(LEGACY_KEY, destinations = null, lastUsed = 1_000_000L)

        migration.previousKey(savedKey = null, newKey = PUBLIC_KEY) shouldBeEqualTo LEGACY_KEY
    }

    @Test
    fun previousKey_givenSeveralUnknownKeys_expectNoneTakenAndLogged() {
        givenStorageForKey(LEGACY_KEY, destinations = null, lastUsed = 1_000_000L)
        givenStorageForKey(OTHER_LEGACY_KEY, destinations = null, lastUsed = 2_000_000L)

        migration.previousKey(savedKey = null, newKey = PUBLIC_KEY).shouldBeNull()
        verify { mockLogger.info(match { it.contains("none were moved") }, any()) }
    }

    // Apps can also run Segment's own SDK with the same storage names
    @Test
    fun previousKey_givenSegmentOwnedStorage_expectItIgnored() {
        givenStorageForKey(SEGMENT_KEY, destinations = listOf(SEGMENT_DESTINATION), lastUsed = 3_000_000L)

        migration.previousKey(savedKey = null, newKey = PUBLIC_KEY).shouldBeNull()

        givenStorageForKey(LEGACY_KEY, destinations = listOf(CUSTOMER_IO_DESTINATION), lastUsed = 1_000_000L)

        migration.previousKey(savedKey = null, newKey = PUBLIC_KEY) shouldBeEqualTo LEGACY_KEY
    }

    @Test
    fun previousKey_givenSwitchToLegacyKey_expectNoneFound() {
        givenStorageForKey(LEGACY_KEY, destinations = listOf(CUSTOMER_IO_DESTINATION), lastUsed = 1_000_000L)

        migration.previousKey(savedKey = null, newKey = OTHER_LEGACY_KEY).shouldBeNull()
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
    fun copyIdentity_givenSwitchToPublicKey_expectAppBuildAndDeviceIdCopied() = runBlocking<Unit> {
        segmentStorage(LEGACY_KEY).apply {
            write(Storage.Constants.AppVersion, "0.9.0")
            write(Storage.Constants.AppBuild, "90")
            write(Storage.Constants.DeviceId, "device-id")
        }
        // Saved as a number by Segment's older SDK
        applicationMock.getSharedPreferences("analytics-android-$LEGACY_KEY", Context.MODE_PRIVATE).edit()
            .putInt(Storage.Constants.LegacyAppBuild.rawVal, 80).commit()

        migration.copyIdentity(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)

        val newStorage = segmentStorage(PUBLIC_KEY)
        newStorage.read(Storage.Constants.AppVersion) shouldBeEqualTo "0.9.0"
        newStorage.read(Storage.Constants.AppBuild) shouldBeEqualTo "90"
        newStorage.read(Storage.Constants.LegacyAppBuild) shouldBeEqualTo "80"
        newStorage.read(Storage.Constants.DeviceId) shouldBeEqualTo "device-id"
    }

    @Test
    fun copyIdentity_givenSwitchToPublicKeyInUpdatedApp_expectApplicationUpdatedNotInstalled() = runBlocking<Unit> {
        givenIdentityForKey(LEGACY_KEY)
        // The app was on an older build when it last ran with the previous key
        segmentStorage(LEGACY_KEY).apply {
            write(Storage.Constants.AppVersion, "0.9.0")
            write(Storage.Constants.AppBuild, "90")
        }

        migration.copyIdentity(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY)
        val tracks = lifecycleEventsOnLaunch(PUBLIC_KEY)

        tracks.map { it.event } shouldBeEqualTo listOf("Application Updated")
        tracks.first().properties["previous_build"]?.jsonPrimitive?.content shouldBeEqualTo "90"
        tracks.first().anonymousId shouldBeEqualTo ANONYMOUS_ID
    }

    @Test
    fun copyIdentity_givenNoCopy_expectApplicationInstalled() {
        // Shows the launch check above would report a fresh install without the copy
        lifecycleEventsOnLaunch(PUBLIC_KEY).map { it.event } shouldBeEqualTo listOf("Application Installed")
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
    fun moveUnfinishedEvents_givenBatchLeftByEarlierKeyChange_expectItMovedToo() {
        givenStorageForKey(OTHER_LEGACY_KEY, destinations = listOf(CUSTOMER_IO_DESTINATION), lastUsed = 1_000_000L)
        eventsFile("$OTHER_LEGACY_KEY-4.tmp").writeText("""{"batch":[$SECOND_EVENT""")
        eventsFile("$LEGACY_KEY-1.tmp").writeText("""{"batch":[$FIRST_EVENT""")
        val newStorage = segmentStorage(PUBLIC_KEY)

        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY, newStorage = newStorage)

        eventsFile("$OTHER_LEGACY_KEY-4.tmp").exists().shouldBeFalse()
        eventsFile("$LEGACY_KEY-1.tmp").exists().shouldBeFalse()
        uploadedEvents(newStorage).let { uploaded ->
            uploaded shouldContain FIRST_EVENT
            uploaded shouldContain SECOND_EVENT
        }
    }

    // Apps can also run Segment's own SDK, which keeps its open batch in the same folder
    @Test
    fun moveUnfinishedEvents_givenOtherSdksUnfinishedBatch_expectItUntouched() {
        givenStorageForKey(SEGMENT_KEY, destinations = listOf(SEGMENT_DESTINATION), lastUsed = 3_000_000L)
        givenStorageForKey(OTHER_LEGACY_KEY, destinations = null, lastUsed = 2_000_000L)
        eventsFile("$SEGMENT_KEY-2.tmp").writeText("""{"batch":[$SECOND_EVENT""")
        eventsFile("$OTHER_LEGACY_KEY-3.tmp").writeText("""{"batch":[$SECOND_EVENT""")
        eventsFile("$LEGACY_KEY-1.tmp").writeText("""{"batch":[$FIRST_EVENT""")
        val newStorage = segmentStorage(PUBLIC_KEY)

        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY, newStorage = newStorage)

        eventsFile("$SEGMENT_KEY-2.tmp").readText() shouldBeEqualTo """{"batch":[$SECOND_EVENT"""
        eventsFile("$OTHER_LEGACY_KEY-3.tmp").readText() shouldBeEqualTo """{"batch":[$SECOND_EVENT"""
        eventsFile("$LEGACY_KEY-1.tmp").exists().shouldBeFalse()
        uploadedEvents(newStorage).let { uploaded ->
            uploaded shouldContain FIRST_EVENT
            uploaded shouldNotContain SECOND_EVENT
        }
    }

    @Test
    fun moveUnfinishedEvents_givenUnknownBatchFormat_expectFileKept() {
        eventsFile("$LEGACY_KEY-1.tmp").writeText("not a batch")

        moveUnfinishedEvents(previousKey = LEGACY_KEY, newKey = PUBLIC_KEY, newStorage = segmentStorage(PUBLIC_KEY))

        eventsFile("$LEGACY_KEY-1.tmp").readText() shouldBeEqualTo "not a batch"
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
        private const val SEGMENT_KEY = "SeGmEnTwRiTeKeY0123456789"
        private const val SEGMENT_DESTINATION = "Segment.io"
        private const val CUSTOMER_IO_DESTINATION = "Customer.io Data Pipelines"
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

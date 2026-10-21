package io.customer.geofence.polygon

import android.content.Context
import android.content.SharedPreferences
import io.customer.commontest.core.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PolygonDwellWakeRecordStoreTest : RobolectricTest() {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences(PolygonDwellWakeRecordStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val store = PolygonDwellWakeRecordStore(prefs)

    @Test
    fun remove_givenNewerVisitArmedSince_expectOlderDisarmLeavesItRecorded() {
        val newer = record("p", visitId = "b")
        val unbooted = record("q").copy(bootSessionId = null)
        store.put(record("p", visitId = "a")).shouldBeTrue()
        store.put(newer).shouldBeTrue()
        store.put(unbooted).shouldBeTrue()

        store.remove("p", "a").shouldBeFalse()
        store.read() shouldBeEqualTo PolygonDwellWakeRecords.Known(mapOf("p" to newer, "q" to unbooted))

        store.remove("p", "b").shouldBeTrue()
        store.read() shouldBeEqualTo PolygonDwellWakeRecords.Known(mapOf("q" to unbooted))
    }

    @Test
    fun read_givenUnreadableValue_expectPossiblyArmedAndNoArmUntilCleared() {
        val key = PolygonDwellWakeRecordStore.KEY
        listOf<SharedPreferences.Editor.() -> Unit>(
            { putString(key, "{not json") },
            { putString(key, """{"p":{"visit":"a"}}""") },
            { putString(key, """{"p":{"visit":"a","rev":1,"gen":1,"entry":-5}}""") },
            { putInt(key, 3) }
        ).forEach { corrupt ->
            prefs.edit().also { it.corrupt() }.commit()

            store.read() shouldBeEqualTo PolygonDwellWakeRecords.Unknown
            store.put(record("p")).shouldBeFalse()
            store.remove("p", "v").shouldBeFalse()
            store.read() shouldBeEqualTo PolygonDwellWakeRecords.Unknown

            store.clear().shouldBeTrue()
            store.put(record("p")).shouldBeTrue()
            store.clear().shouldBeTrue()
        }
    }

    @Test
    fun put_givenCommitFails_expectFalseSoTheCallerDoesNotArm() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.commit() } returns false
        val failing = mockk<SharedPreferences>()
        every { failing.getString(any(), any()) } returns null
        every { failing.edit() } returns editor

        PolygonDwellWakeRecordStore(failing).put(record("p")).shouldBeFalse()
    }

    @Test
    fun put_givenFullOrUnboundedRecord_expectRefusedAndExistingRecordsKept() {
        store.put(record("x".repeat(101))).shouldBeFalse()
        store.put(record("p", visitId = "")).shouldBeFalse()
        (1..PolygonDwellWakeRecordStore.MAX_RECORDS).forEach { store.put(record("p$it")).shouldBeTrue() }

        store.put(record("extra")).shouldBeFalse()
        // A recorded polygon's next visit reuses its slot.
        store.put(record("p1", visitId = "next")).shouldBeTrue()

        val known = store.read() as PolygonDwellWakeRecords.Known
        known.byPolygonId.size shouldBeEqualTo PolygonDwellWakeRecordStore.MAX_RECORDS
        known.byPolygonId.getValue("p1").visitId shouldBeEqualTo "next"
    }

    @Test
    fun put_givenEscapedRecordsWouldOutgrowWhatReadAccepts_expectRefusedBeforeSlotsRunOutAndHistoryKept() {
        // A control character encodes as a six-character escape, so the encoded cap binds first.
        val control = Char(1).toString().repeat(100)
        var accepted = emptyMap<String, PolygonDwellWakeRecord>()
        for (slot in 1..PolygonDwellWakeRecordStore.MAX_RECORDS) {
            val next = record("p$slot".padEnd(100, 'x'), visitId = control).copy(bootSessionId = control)
            if (!store.put(next)) break
            accepted = accepted + (next.polygonId to next)
            // An accepted arm must read back, or every later put and remove sees Unknown.
            store.read() shouldBeEqualTo PolygonDwellWakeRecords.Known(accepted)
        }

        (accepted.size in 1 until PolygonDwellWakeRecordStore.MAX_RECORDS).shouldBeTrue()
        store.read() shouldBeEqualTo PolygonDwellWakeRecords.Known(accepted)
    }

    @Test
    fun put_givenCommitFailsWithTheEditLeftInMemory_expectWhatWasReadWrittenBack() {
        val older = record("p", visitId = "older")
        store.put(older).shouldBeTrue()
        val failing = PolygonDwellWakeRecordStore(
            ScriptedCommitPreferences(prefs).apply {
                failNextCommits = 1
                keepFailedEdits = true
            }
        )

        failing.put(record("p", visitId = "newer")).shouldBeFalse()

        // The newer visit was never armed, while the older visit's wake may still be live.
        failing.read() shouldBeEqualTo PolygonDwellWakeRecords.Known(mapOf("p" to older))
    }

    private fun record(polygonId: String, visitId: String = "v") = PolygonDwellWakeRecord(
        polygonId = polygonId,
        visitId = visitId,
        regionRevision = 42,
        userStateGeneration = 7L,
        bootSessionId = "boot-3",
        entryElapsedMs = 120_000L
    )
}

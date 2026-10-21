package io.customer.geofence

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import io.customer.commontest.core.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceExperimentsTest : RobolectricTest() {
    @Suppress("DEPRECATION")
    private fun experiments(applicationInfo: () -> ApplicationInfo): GeofenceExperiments {
        val packageManager = mockk<PackageManager>()
        every { packageManager.getApplicationInfo("host.app", PackageManager.GET_META_DATA) } answers {
            applicationInfo()
        }
        val context = mockk<Context>()
        every { context.packageName } returns "host.app"
        every { context.packageManager } returns packageManager
        return GeofenceExperiments(context)
    }

    private fun declaring(declared: Bundle?) = experiments { ApplicationInfo().apply { metaData = declared } }

    @Test
    fun dwellDiagnosticJournalEnabled_givenHostDeclaresTrue_expectOn() {
        val declared = Bundle().apply { putBoolean(GeofenceExperiments.KEY_DWELL_DIAGNOSTIC_JOURNAL, true) }

        declaring(declared).dwellDiagnosticJournalEnabled().shouldBeTrue()
    }

    @Test
    fun dwellDiagnosticJournalEnabled_givenNoDeclarationOrUnreadableManifest_expectOff() {
        declaring(declared = null).dwellDiagnosticJournalEnabled().shouldBeFalse()
        declaring(Bundle()).dwellDiagnosticJournalEnabled().shouldBeFalse()
        experiments { throw PackageManager.NameNotFoundException() }
            .dwellDiagnosticJournalEnabled().shouldBeFalse()
    }

    @Test
    fun polygonDwellWakeEnabled_givenEachExperimentDeclaredAlone_expectOnlyThatOneOn() {
        val wakeOnly = declaring(Bundle().apply { putBoolean(GeofenceExperiments.KEY_POLYGON_DWELL_WAKE, true) })
        val journalOnly =
            declaring(Bundle().apply { putBoolean(GeofenceExperiments.KEY_DWELL_DIAGNOSTIC_JOURNAL, true) })

        wakeOnly.polygonDwellWakeEnabled().shouldBeTrue()
        wakeOnly.dwellDiagnosticJournalEnabled().shouldBeFalse()
        // A host collecting diagnostics must not also start re-adding GMS registrations.
        journalOnly.polygonDwellWakeEnabled().shouldBeFalse()
    }
}

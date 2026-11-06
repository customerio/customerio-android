package io.customer.messagingpush.store

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushSettingsStoreTest : IntegrationTest() {

    @Suppress("DEPRECATION")
    private fun contextWithInstalledApp(versionCode: Int, lastUpdateTime: Long): Context {
        val packageManager = mockk<PackageManager>()
        every { packageManager.getPackageInfo("com.example.app", 0) } returns PackageInfo().apply {
            this.versionCode = versionCode
            this.lastUpdateTime = lastUpdateTime
        }
        return mockk {
            every { packageName } returns "com.example.app"
            every { this@mockk.packageManager } returns packageManager
        }
    }

    @Test
    fun installedAppVersion_givenSameVersionReinstalled_expectNewIdentity() {
        val firstInstall = PushSettingsStore.installedAppVersion(contextWithInstalledApp(versionCode = 7, lastUpdateTime = 1_000))
        val reinstall = PushSettingsStore.installedAppVersion(contextWithInstalledApp(versionCode = 7, lastUpdateTime = 2_000))

        reinstall shouldNotBeEqualTo firstInstall
    }

    @Test
    fun installedAppVersion_givenUpgrade_expectNewIdentity() {
        val before = PushSettingsStore.installedAppVersion(contextWithInstalledApp(versionCode = 7, lastUpdateTime = 1_000))
        val after = PushSettingsStore.installedAppVersion(contextWithInstalledApp(versionCode = 8, lastUpdateTime = 1_000))

        after shouldNotBeEqualTo before
    }

    @Test
    fun installedAppVersion_givenSameInstall_expectSameIdentity() {
        val first = PushSettingsStore.installedAppVersion(contextWithInstalledApp(versionCode = 7, lastUpdateTime = 1_000))
        val second = PushSettingsStore.installedAppVersion(contextWithInstalledApp(versionCode = 7, lastUpdateTime = 1_000))

        second shouldBeEqualTo first
    }

    @Test
    fun autoTrackPushEvents_givenPackageInfoUnavailable_expectUnknownAndNothingSaved() {
        val packageManager = mockk<PackageManager>()
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        val context = mockk<Context> {
            every { packageName } returns "com.example.app"
            every { this@mockk.packageManager } returns packageManager
        }
        val store = PushSettingsStore(applicationMock) { PushSettingsStore.installedAppVersion(context) }

        store.saveAutoTrackPushEvents(false)

        store.autoTrackPushEvents().shouldBeNull()
    }
}

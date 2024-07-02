package io.customer.commontest.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.After
import org.junit.Before

abstract class RobolectricTest : UnitTest() {
    final override val applicationMock: Application = spyk(ApplicationProvider.getApplicationContext())
    final override val contextMock: Context = spyk(InstrumentationRegistry.getInstrumentation().targetContext)

    init {
        val testPackageName = "com.example.test_app"

        val applicationInfoMock = mockk<ApplicationInfo>(relaxed = true)
        val packageInfo = PackageInfo().apply {
            versionCode = 100
            versionName = "1.0.0"
            applicationInfo = applicationInfoMock
        }
        val packageManagerMock = mockk<PackageManager>(relaxed = true) {
            every { this@mockk.getPackageInfo(testPackageName, 0) } returns packageInfo
            every { this@mockk.getApplicationInfo(testPackageName, 0) } returns applicationInfoMock
        }

        every { applicationMock.applicationContext } returns applicationMock
        every { applicationMock.packageName } returns testPackageName
        every { applicationMock.packageManager } returns packageManagerMock

        every { contextMock.applicationContext } returns applicationMock
        every { contextMock.packageName } returns applicationMock.packageName
        every { contextMock.packageManager } returns applicationMock.packageManager
    }

    @Before
    fun setupTestEnvironment() {
        setup()
    }

    @After
    fun teardownTestEnvironment() {
        teardown()
    }
}

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

/**
 * Robolectric test base class for all Robolectric tests in the project.
 * This class is responsible for basic setup and teardown of Robolectric test environment.
 * The class should only contain the common setup and teardown logic for all Robolectric tests.
 * Any additional setup or teardown logic should be implemented in respective child classes.
 * This class is responsible for mocking Android application and context objects.
 *
 * Tests extending this class should make sure to import JUnit5 imports for test annotations.
 * e.g. import org.junit.Test
 */
abstract class RobolectricTest : UnitTest() {
    final override val applicationMock: Application = spyk(ApplicationProvider.getApplicationContext())
    final override val contextMock: Context = spyk(InstrumentationRegistry.getInstrumentation().targetContext)

    init {
        val testPackageName = "com.example.test_app"

        val applicationInfoMock = mockk<ApplicationInfo>(relaxed = true)
        val packageInfo = PackageInfo().apply {
            @Suppress("DEPRECATION")
            versionCode = 100
            versionName = "1.0.0"
            applicationInfo = applicationInfoMock
        }
        val packageManagerMock = mockk<PackageManager>(relaxed = true) {
            @Suppress("DEPRECATION")
            every { this@mockk.getPackageInfo(testPackageName, 0) } returns packageInfo
            @Suppress("DEPRECATION")
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

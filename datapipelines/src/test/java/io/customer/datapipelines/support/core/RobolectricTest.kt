package io.customer.datapipelines.support.core

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.After
import org.junit.Before

/**
 * Extension of the [UnitTest] class to provide setup and teardown methods for
 * Robolectric tests. Since Robolectric is not compatible with JUnit 5, this class
 * uses JUnit 4 annotations to provide setup and teardown methods for the test environment.
 * The class uses mock application instance to allow running tests using Robolectric API for Android.
 */
open class RobolectricTest : UnitTest() {
    protected val mockContext: Context = spyk(InstrumentationRegistry.getInstrumentation().targetContext)
    protected val mockApplication: Application

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

        mockApplication = spyk(spyk<Application>(mockContext.applicationContext as Application)) {
            every { this@spyk.packageName } returns testPackageName
            every { this@spyk.packageManager } returns packageManagerMock
            every { this@spyk.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) } returns PackageManager.PERMISSION_GRANTED
        }
    }

    override var testApplication: Any = mockApplication

    @Before
    open fun setup() {
        setupTestEnvironment()
    }

    @After
    open fun teardown() {
        deinitializeModule()
    }
}

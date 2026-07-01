package io.customer.messagingpush.livenotification.template

import android.content.res.Resources
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [TemplateAssets].
 *
 * The host app's drawable namespace isn't reachable from a unit-test runtime,
 * so these tests exercise the boundary behavior every template depends on:
 * - null / blank keys return `null` without logging warnings;
 * - non-resolvable keys return `null` and surface a debug log so developers
 *   can diagnose missing assets without crashing the notification.
 *
 * We intentionally do not assert kebab-to-underscore normalization on a real
 * resource lookup because the test runtime doesn't ship the host app's
 * drawables; the normalization is exercised by hand-mocking the resource lookup
 * in `resolveDrawable_kebabCaseKey_isNormalizedToUnderscores`.
 */
@RunWith(RobolectricTestRunner::class)
internal class TemplateAssetsTest : IntegrationTest() {

    private val mockLogger: Logger = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                    }
                }
            }
        )
    }

    @Test
    fun resolveDrawable_nullKey_returnsNullWithoutLogging() {
        val result = TemplateAssets.resolveDrawable(contextMock, null)

        result.shouldBeNull()
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun resolveDrawable_blankKey_returnsNullWithoutLogging() {
        val result = TemplateAssets.resolveDrawable(contextMock, "   ")

        result.shouldBeNull()
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun resolveDrawable_emptyKey_returnsNullWithoutLogging() {
        val result = TemplateAssets.resolveDrawable(contextMock, "")

        result.shouldBeNull()
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun resolveDrawable_unresolvedKey_returnsNullAndLogsWarning() {
        val message = slot<String>()
        every { mockLogger.debug(capture(message), any()) } returns Unit

        val result = TemplateAssets.resolveDrawable(contextMock, "no_such_drawable_in_test_app")

        result.shouldBeNull()
        assertCalledOnce { mockLogger.debug(any(), any()) }
        message.captured shouldContain "did not resolve to a drawable"
    }

    @Test
    fun resolveDrawable_kebabCaseKey_isNormalizedToUnderscores() {
        // We can't actually resolve a host drawable from a unit-test runtime, so we
        // hand-mock the underlying resources lookup and assert it received the
        // normalized name. This is what locks the kebab-to-underscore rule.
        val mockResources = mockk<Resources>()
        // Underscored form must be queried; if the SDK forwards the kebab form, this
        // expectation fails because no `every` was set up for it.
        every {
            mockResources.getIdentifier("delivery_warehouse", "drawable", any())
        } returns 12345

        every { contextMock.resources } returns mockResources

        val result = TemplateAssets.resolveDrawable(contextMock, "delivery-warehouse")

        result.shouldNotBeNull()
    }

    @Test
    fun resolveBitmap_nullKey_returnsNull() {
        val result = TemplateAssets.resolveBitmap(contextMock, null)

        result.shouldBeNull()
    }

    @Test
    fun resolveBitmap_unresolvedKey_returnsNull() {
        val result = TemplateAssets.resolveBitmap(contextMock, "no_such_drawable_in_test_app")

        result.shouldBeNull()
    }

    @Test
    fun resolveBitmap_hostRegisteredBytesAsset_resolvesByKey() {
        // A key the host app registered via registerLiveNotificationAsset resolves
        // ahead of the drawable-name lookup.
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .registerLiveNotificationAsset("brand-logo", byteArrayOf(1, 2, 3, 4))
                .build()
        ).attachToSDKComponent()

        val result = TemplateAssets.resolveBitmap(contextMock, "brand-logo")

        result.shouldNotBeNull()
    }
}

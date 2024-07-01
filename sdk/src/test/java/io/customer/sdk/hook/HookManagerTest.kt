package io.customer.sdk.hook

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.commontest.BaseTest
import io.customer.sdk.extensions.random
import io.customer.sdk.hooks.CioHooksManager
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.ModuleHook
import io.customer.sdk.hooks.ModuleHookProvider
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class HookManagerTest : BaseTest() {

    private lateinit var cioHooksManager: CioHooksManager

    @Before
    override fun setup() {
        super.setup()

        cioHooksManager = CioHooksManager()
    }

    @Test
    fun subscribeToUpdate_givenSubscribedToHooksManager_expectGetHookUpdate() {
        val identifier = String.random
        val profileIdentifiedHook = ModuleHook.ProfileIdentifiedHook(identifier)

        val beforeProfileStoppedBeingIdentifiedHook =
            ModuleHook.BeforeProfileStoppedBeingIdentified(identifier)

        val screen = String.random
        val screenTrackedHook = ModuleHook.ScreenTrackedHook(screen)

        var didProfileIdentifyHookGetCalled = false
        var didBeforeProfileStoppedBeingIdentifiedGetCalled = false
        var didScreenTrackedHookGetCalled = false

        cioHooksManager.add(
            HookModule.MessagingInApp,
            object : ModuleHookProvider() {
                override fun profileIdentifiedHook(hook: ModuleHook.ProfileIdentifiedHook) {
                    didProfileIdentifyHookGetCalled = true
                    assertEquals(hook, profileIdentifiedHook)
                    assertEquals(hook.identifier, identifier)
                }

                override fun beforeProfileStoppedBeingIdentified(hook: ModuleHook.BeforeProfileStoppedBeingIdentified) {
                    didBeforeProfileStoppedBeingIdentifiedGetCalled = true
                    assertEquals(hook, beforeProfileStoppedBeingIdentifiedHook)
                    assertEquals(hook.identifier, identifier)
                }

                override fun screenTrackedHook(hook: ModuleHook.ScreenTrackedHook) {
                    didScreenTrackedHookGetCalled = true
                    assertEquals(hook, screenTrackedHook)
                    assertEquals(hook.screen, screen)
                }
            }
        )

        val availableHooks = listOf(
            profileIdentifiedHook,
            beforeProfileStoppedBeingIdentifiedHook,
            screenTrackedHook
        )

        // its enum so whenever a new enum gets added, test would need updating as well
        availableHooks.forEach {
            when (it) {
                is ModuleHook.BeforeProfileStoppedBeingIdentified -> {
                    cioHooksManager.onHookUpdate(profileIdentifiedHook)
                }
                is ModuleHook.ProfileIdentifiedHook -> {
                    cioHooksManager.onHookUpdate(beforeProfileStoppedBeingIdentifiedHook)
                }
                is ModuleHook.ScreenTrackedHook -> {
                    cioHooksManager.onHookUpdate(screenTrackedHook)
                }
            }
        }

        didProfileIdentifyHookGetCalled shouldBeEqualTo true
        didBeforeProfileStoppedBeingIdentifiedGetCalled shouldBeEqualTo true
        didScreenTrackedHookGetCalled shouldBeEqualTo true
    }
}

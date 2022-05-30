package io.customer.sdk.hook

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.hooks.CioHooksManager
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.ModuleHook
import io.customer.sdk.hooks.ModuleHookProvider
import io.customer.sdk.utils.random
import org.amshove.kluent.internal.assertEquals
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

        val beforeProfileStoppedBeingIdentifiedHook = ModuleHook.BeforeProfileStoppedBeingIdentified(identifier)

        val screen = String.random
        val screenTrackedHook = ModuleHook.ScreenTrackedHook(screen)

        cioHooksManager.add(
            HookModule.MessagingInApp,
            object : ModuleHookProvider() {
                override fun profileIdentifiedHook(hook: ModuleHook.ProfileIdentifiedHook) {
                    assertEquals(hook, profileIdentifiedHook)
                    assertEquals(hook.identifier, identifier)
                }

                override fun beforeProfileStoppedBeingIdentified(hook: ModuleHook.BeforeProfileStoppedBeingIdentified) {
                    assertEquals(hook, beforeProfileStoppedBeingIdentifiedHook)
                    assertEquals(hook.identifier, identifier)
                }

                override fun screenTrackedHook(hook: ModuleHook.ScreenTrackedHook) {
                    assertEquals(hook, screenTrackedHook)
                    assertEquals(hook.screen, screen)
                }
            }
        )

        cioHooksManager.onHookUpdate(profileIdentifiedHook)
        cioHooksManager.onHookUpdate(beforeProfileStoppedBeingIdentifiedHook)
        cioHooksManager.onHookUpdate(screenTrackedHook)
    }
}

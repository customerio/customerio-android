package io.customer.sdk.hooks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.hooks.hooks.QueueRunnerHook
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class HooksManagerTest : BaseTest() {

    private lateinit var hooksManager: HooksManager

    @Before
    override fun setup() {
        super.setup()

        hooksManager = HooksManagerImpl()
    }

    @Test
    fun givenNoProviders_expectGetEmptyListForHooks() {
        val actual = hooksManager.profileIdentifiedHooks

        actual shouldBeEqualTo emptyList()
    }

    @Test
    fun givenProviderAdded_expectGetHooksFromHooksProvider() {
        hooksManager.addProvider(
            HookModule.MESSAGING_PUSH,
            object : ModuleHookProvider {
                override val profileIdentifiedHook: ProfileIdentifiedHook
                    get() = mock()
                override val queueRunnerHook: QueueRunnerHook?
                    get() = null
            }
        )

        hooksManager.profileIdentifiedHooks.count() shouldBeEqualTo 1
        hooksManager.queueRunnerHooks.count() shouldBeEqualTo 0
    }
}

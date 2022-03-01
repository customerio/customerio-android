package io.customer.sdk.hooks

import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.hooks.hooks.QueueRunnerHook

interface HooksManager {
    fun addProvider(key: HookModule, provider: ModuleHookProvider)
    val profileIdentifiedHooks: List<ProfileIdentifiedHook>
    val queueRunnerHooks: List<QueueRunnerHook>
}

// all of the modules in this project that contain hooks in it.
enum class HookModule {
    MESSAGING_PUSH
}

/**
 * Singleton that manages hook providers for all of the project modules.
 *
 * Used to get hooks used throughout the SDK project.
 */
class HooksManagerImpl : HooksManager {

    private val hooksProviders: MutableMap<HookModule, ModuleHookProvider> = mutableMapOf()

    // / using key/value pairs enforces that there is only 1 hook provider for each
    // / module without having duplicates.
    override fun addProvider(key: HookModule, provider: ModuleHookProvider) {
        hooksProviders[key] = provider
    }

    override val profileIdentifiedHooks: List<ProfileIdentifiedHook>
        get() = hooksProviders.mapNotNull { it.value.profileIdentifiedHook }

    override val queueRunnerHooks: List<QueueRunnerHook>
        get() = hooksProviders.mapNotNull { it.value.queueRunnerHook }
}

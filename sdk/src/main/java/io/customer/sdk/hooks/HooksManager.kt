package io.customer.sdk.hooks

import io.customer.sdk.util.Singleton

interface HooksManager {
    fun add(modules: HookModule, subscriber: ModuleHookProvider)
    fun onHookUpdate(hook: ModuleHook?)
}

enum class HookModule {
    MessagingPush, MessagingInApp
}

internal class CioHooksManager : HooksManager {

    companion object SingletonHolder : Singleton<HooksManager>()

    private val map: MutableMap<HookModule, ModuleHookProvider> = mutableMapOf()

    override fun add(module: HookModule, subscriber: ModuleHookProvider) {
        map[module] = subscriber
    }

    override fun onHookUpdate(hook: ModuleHook?) {
        when (hook) {
            is ModuleHook.ProfileIdentifiedHook -> map.values.forEach {
                it.profileIdentifiedHook(hook)
            }
            is ModuleHook.BeforeProfileStoppedBeingIdentified -> map.values.forEach {
                it.beforeProfileStoppedBeingIdentified(hook)
            }
            is ModuleHook.ScreenTrackedHook -> map.values.forEach {
                it.screenTrackedHook(hook)
            }
        }
    }
}

package io.customer.sdk.hooks

import io.customer.sdk.util.Singleton

interface HooksManager {
    fun add(modules: HookModules, subscriber: ModuleHookProvider)
    fun onHookUpdate(hook: ModuleHook)
}

enum class HookModules {
    MessagingPush, MessagingInApp
}

internal class CioHooksManager : HooksManager {

    companion object SingletonHolder : Singleton<HooksManager>()

    private val map: MutableMap<HookModules, ModuleHookProvider> = mutableMapOf()

    override fun add(modules: HookModules, subscriber: ModuleHookProvider) {
        map[modules] = subscriber
    }

    override fun onHookUpdate(hook: ModuleHook) {
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

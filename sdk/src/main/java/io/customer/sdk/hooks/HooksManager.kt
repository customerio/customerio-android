package io.customer.sdk.hooks

interface HooksManager {
    fun add(subscriber: ModuleHookProvider)
    fun onHookUpdate(hook: ModuleHook)
}

internal class CioHooksManager : HooksManager {

    val list: MutableList<ModuleHookProvider> = mutableListOf()

    override fun add(subscriber: ModuleHookProvider) {
        list.add(subscriber)
    }

    override fun onHookUpdate(hook: ModuleHook) {
        when (hook) {
            is ModuleHook.ProfileIdentifiedHook -> list.forEach {
                it.profileIdentifiedHook(hook)
            }
            is ModuleHook.BeforeProfileStoppedBeingIdentified -> list.forEach {
                it.beforeProfileStoppedBeingIdentified(hook)
            }
            is ModuleHook.ScreenTrackedHook -> list.forEach {
                it.screenTrackedHook(hook)
            }
        }
    }
}

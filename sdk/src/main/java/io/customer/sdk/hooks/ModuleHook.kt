package io.customer.sdk.hooks

abstract class ModuleHookProvider {
    open fun profileIdentifiedHook(hook: ModuleHook.ProfileIdentifiedHook) {}
    open fun beforeProfileStoppedBeingIdentified(hook: ModuleHook.BeforeProfileStoppedBeingIdentified) {}
    open fun screenTrackedHook(hook: ModuleHook.ScreenTrackedHook) {}
    open fun dismiss(hook: ModuleHook.Dismiss) {}
}

sealed class ModuleHook {

    // Hook to notify when a profile is newly identified in the SDK.
    class ProfileIdentifiedHook(val identifier: String) : ModuleHook()

    // Hook to notify when profile is stopped being identified.
    class BeforeProfileStoppedBeingIdentified(val identifier: String) : ModuleHook()

    // Hook to notify when a screen is tracked in SDK.
    class ScreenTrackedHook(val screen: String) : ModuleHook()

    // Dismiss the in-app message being shown
    object Dismiss : ModuleHook()
}

package io.customer.sdk.hooks.hooks

// hooks all dealing with events related to profiles being identified.
interface ProfileIdentifiedHook {
    // called when switching to a new profile. Only called when
    // `oldIdentifier != newIdentifier`
    fun beforeIdentifiedProfileChange(oldIdentifier: String, newIdentifier: String)
    // called when a profile is newly identified in the SDK.
    fun profileIdentified(identifier: String)
    // profile previously identified has stopped being identified.
    // called only when there was a profile that was previously identified
    fun profileStoppedBeingIdentified(oldIdentifier: String)
}

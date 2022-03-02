package io.customer.sdk.hooks

import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.hooks.hooks.QueueRunnerHook

/**
 * Provider constructs new instances of hooks when requested.
 *
 * We want to try and limit singletons to avoid circular dependencies
 * caused by initializing too many classes. So, initialize instances when requested.
 *
 * The SDK can choose to return `null` for any hook if the module has no need to listen to an event type.
 */
interface ModuleHookProvider {
    val profileIdentifiedHook: ProfileIdentifiedHook?
    val queueRunnerHook: QueueRunnerHook?
}

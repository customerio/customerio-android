package io.customer.sdk.hooks

import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.hooks.hooks.QueueRunnerHook

/**
 * Provider constructs new instances of hooks when requested.
 *
 * We want to try and limit singletons to avoid circular dependencies
 * caused by initializing too many classes. So, initialize instances when requested.
 */
interface ModuleHookProvider {
    val profileIdentifiedHook: ProfileIdentifiedHook?
    val queueRunnerHook: QueueRunnerHook?
}

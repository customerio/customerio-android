package io.customer.messagingpush.hooks

import io.customer.messagingpush.MessagingPush
import io.customer.messagingpush.di.MessagingPushDiGraph
import io.customer.sdk.hooks.ModuleHookProvider
import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.hooks.hooks.QueueRunnerHook

class MessagingPushModuleHookProvider(private val siteId: String) : ModuleHookProvider {

    private val messagingPushDiGraph: MessagingPushDiGraph
        get() = MessagingPushDiGraph.getInstance(siteId)

    override val profileIdentifiedHook: ProfileIdentifiedHook
        get() = MessagingPush(siteId)

    override val queueRunnerHook: QueueRunnerHook
        get() = messagingPushDiGraph.queueRunner
}

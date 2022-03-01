package io.customer.messagingpush.di

import io.customer.messagingpush.queue.MessagingPushQueueRunner
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.di.DiGraph
import io.customer.sdk.di.DiGraphCompanion
import io.customer.sdk.di.override

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
internal class MessagingPushDiGraph(
    private val siteId: String
) : DiGraph {

    companion object : DiGraphCompanion<MessagingPushDiGraph>() {
        override fun newInstance(siteId: String) = MessagingPushDiGraph(siteId)
    }

    override var overrides: MutableMap<String, Any> = mutableMapOf()

    val trackingDiGraph: CustomerIOComponent
        get() = CustomerIOComponent.getInstance(siteId)

    val queueRunner: MessagingPushQueueRunner
        get() = override() ?: MessagingPushQueueRunner()
}

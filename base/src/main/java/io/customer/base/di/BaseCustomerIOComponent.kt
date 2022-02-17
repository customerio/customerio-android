package io.customer.base.di

import android.content.Context
import io.customer.sdk.queue.QueueStorage
import io.customer.sdk.queue.QueueStorageImpl

/**
 * Configuration class to configure/initialize low-level operations and objects.
 */
internal class BaseCustomerIOComponent(
    private val context: Context
) {

    // TODO
    // Move all properties from the Tracking module's CustomerIOComponent into this component *except*
    // the public classes that are unique to the Tracking module.
    //
    // this would include moshi, retrofit, the background queue, etc.

    val siteId: String
        get() = customerIOConfig.siteId

    val queueStorage: QueueStorage
        get() = QueueStorageImpl(siteId, fileStorage, jsonAdapter)
}

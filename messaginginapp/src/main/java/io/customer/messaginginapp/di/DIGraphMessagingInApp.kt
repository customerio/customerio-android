package io.customer.messaginginapp.di

import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistApiProvider
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.gistApiProvider: GistApi
    get() = newInstance<GistApi> { GistApiProvider() }

internal val SDKComponent.gistProvider: InAppMessagesProvider
    get() = newInstance<InAppMessagesProvider> { GistInAppMessagesProvider(gistApiProvider) }

// We need to add extension functions to the CustomerIO class as this is how
// customers will interact with in-app messaging module.
@Suppress("UnusedReceiverParameter")
fun CustomerIO.inAppMessaging(): ModuleMessagingInApp {
    return SDKComponent.modules[ModuleMessagingInApp.MODULE_NAME] as? ModuleMessagingInApp
        ?: throw IllegalStateException("ModuleMessagingInApp not initialized")
}

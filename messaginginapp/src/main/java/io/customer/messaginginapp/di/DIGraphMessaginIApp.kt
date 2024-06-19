package io.customer.messaginginapp.di

import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistApiProvider
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.gistApiProvider: GistApi
    get() = newInstance<GistApi> { GistApiProvider() }

internal val SDKComponent.gistProvider: InAppMessagesProvider
    get() = newInstance<InAppMessagesProvider> { GistInAppMessagesProvider(gistApiProvider) }

fun SDKComponent.inAppMessaging(): ModuleMessagingInApp {
    return modules[ModuleMessagingInApp.moduleName] as? ModuleMessagingInApp
        ?: throw IllegalStateException("ModuleMessagingInApp not initialized")
}

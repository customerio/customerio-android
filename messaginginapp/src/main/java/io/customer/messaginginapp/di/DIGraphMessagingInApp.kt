package io.customer.messaginginapp.di

import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.domain.InAppMessagingManager
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.gistQueue: GistQueue
    get() = singleton<GistQueue> { Queue() }

internal val SDKComponent.inAppMessaging: ModuleMessagingInApp
    get() = ModuleMessagingInApp.instance()

internal val SDKComponent.moduleConfig: MessagingInAppModuleConfig
    get() = inAppMessaging.moduleConfig

internal val SDKComponent.gistProvider: GistProvider
    get() = singleton<GistProvider> {
        GistSdk(
            application = android().application,
            siteId = moduleConfig.siteId,
            dataCenter = moduleConfig.region.code
        )
    }
internal val SDKComponent.inAppMessagingManager: InAppMessagingManager
    get() = singleton { InAppMessagingManager(inAppMessaging) }

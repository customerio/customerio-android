package io.customer.messaginginapp.di

import com.google.gson.Gson
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.AnonymousMessageManager
import io.customer.messaginginapp.gist.data.AnonymousMessageManagerImpl
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.data.sse.HeartbeatTimer
import io.customer.messaginginapp.gist.data.sse.SseConnectionManager
import io.customer.messaginginapp.gist.data.sse.SseDataParser
import io.customer.messaginginapp.gist.data.sse.SseRetryHelper
import io.customer.messaginginapp.gist.data.sse.SseService
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.gist.utilities.ModalMessageGsonParser
import io.customer.messaginginapp.gist.utilities.ModalMessageParser
import io.customer.messaginginapp.gist.utilities.ModalMessageParserDefault
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.messaginginapp.store.InAppPreferenceStoreImpl
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.gistQueue: GistQueue
    get() = singleton<GistQueue> { Queue() }

val SDKComponent.inAppModuleConfig: MessagingInAppModuleConfig
    get() = inAppMessaging.moduleConfig

internal val SDKComponent.gistProvider: GistProvider
    get() = singleton<GistProvider> {
        GistSdk(
            siteId = inAppModuleConfig.siteId,
            dataCenter = inAppModuleConfig.region.code
        )
    }

internal val SDKComponent.inAppPreferenceStore: InAppPreferenceStore
    get() = singleton<InAppPreferenceStore> { InAppPreferenceStoreImpl(android().applicationContext) }

internal val SDKComponent.inAppMessagingManager: InAppMessagingManager
    get() = singleton<InAppMessagingManager> { InAppMessagingManager(inAppMessaging) }

internal val SDKComponent.gistSdk: GistSdk
    get() = singleton<GistSdk> {
        GistSdk(siteId = inAppModuleConfig.siteId, dataCenter = inAppModuleConfig.region.code)
    }

internal val SDKComponent.modalMessageParser: ModalMessageParser
    get() = singleton<ModalMessageParser> {
        ModalMessageParserDefault(
            logger = logger,
            dispatchersProvider = dispatchersProvider,
            parser = ModalMessageGsonParser(gson = Gson())
        )
    }

internal val SDKComponent.anonymousMessageManager: AnonymousMessageManager
    get() = singleton<AnonymousMessageManager> {
        AnonymousMessageManagerImpl()
    }

internal val SDKComponent.sseDataParser: SseDataParser
    get() = singleton<SseDataParser> {
        SseDataParser(logger, Gson())
    }

internal val SDKComponent.heartbeatTimer: HeartbeatTimer
    get() = singleton<HeartbeatTimer> {
        HeartbeatTimer(logger, scopeProvider.inAppLifecycleScope)
    }

internal val SDKComponent.sseService: SseService
    get() = singleton<SseService> {
        SseService(
            logger = logger,
            inAppMessagingManager = inAppMessagingManager
        )
    }

internal val SDKComponent.sseRetryHelper: SseRetryHelper
    get() = singleton<SseRetryHelper> {
        SseRetryHelper(
            logger = logger,
            scope = scopeProvider.inAppLifecycleScope
        )
    }

internal val SDKComponent.sseConnectionManager: SseConnectionManager
    get() = singleton<SseConnectionManager> {
        SseConnectionManager(
            logger = logger,
            sseService = sseService,
            sseDataParser = sseDataParser,
            inAppMessagingManager = inAppMessagingManager,
            heartbeatTimer = heartbeatTimer,
            retryHelper = sseRetryHelper,
            scope = scopeProvider.inAppLifecycleScope
        )
    }

/**
 * Get the [ModuleMessagingInApp] instance from the [CustomerIOInstance]
 * needed for the in-app messaging dismiss() method
 */
@Suppress("UnusedReceiverParameter")
fun CustomerIOInstance.inAppMessaging(): ModuleMessagingInApp = SDKComponent.inAppMessaging

internal val SDKComponent.inAppMessaging: ModuleMessagingInApp
    get() = ModuleMessagingInApp.instance()

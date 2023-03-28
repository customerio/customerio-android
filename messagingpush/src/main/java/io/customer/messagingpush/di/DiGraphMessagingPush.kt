package io.customer.messagingpush.di

import io.customer.messagingpush.*
import io.customer.messagingpush.provider.FCMTokenProviderImpl
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.DeepLinkUtilImpl
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.data.store.Client
import io.customer.sdk.device.DeviceTokenProvider
import io.customer.sdk.di.CustomerIOComponent

/*
This file contains a series of extensions to the common module's Dependency injection (DI) graph. All extensions in this file simply add internal classes for this module into the DI graph.

The use of extensions was chosen over creating a separate graph class for each module. This simplifies the SDK code as well as automated tests code dramatically.
 */

internal val CustomerIOComponent.fcmTokenProvider: DeviceTokenProvider
    get() = override() ?: FCMTokenProviderImpl(logger = logger, context = context)

internal val CustomerIOComponent.moduleConfig: MessagingPushModuleConfig
    get() = override() ?: sdkConfig.configurations.getOrElse(
        ModuleMessagingPushFCM.MODULE_NAME
    ) {
        MessagingPushModuleConfig.default()
    } as MessagingPushModuleConfig

internal val CustomerIOComponent.deepLinkUtil: DeepLinkUtil
    get() = override() ?: DeepLinkUtilImpl(logger, moduleConfig)

internal val CustomerIOComponent.pushTrackingUtil: PushTrackingUtil
    get() = override() ?: PushTrackingUtilImpl(trackRepository)

/**
 * Creates processor based on client so notification messages are processed depending on
 * client needs and limitations.
 */
internal val CustomerIOComponent.pushMessageProcessor: CustomerIOFirebaseMessageProcessor
    get() = override() ?: getSingletonInstanceCreate {
        return when (sdkConfig.client) {
            is Client.Android -> CustomerIOFirebaseMessageNativeProcessor(context)
            is Client.Expo,
            is Client.Flutter,
            is Client.Other,
            is Client.ReactNative -> CustomerIOFirebaseMessageWrapperProcessor(
                context = context,
                logger = logger,
                moduleConfig = moduleConfig
            )
        }
    }

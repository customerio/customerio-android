package io.customer.sdk

import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.LogTail
import io.customer.sdk.core.util.Logger
import java.lang.IllegalStateException

internal class DataPipelinesLogger(private val logger: Logger) {

    companion object {
        const val INIT_TAG = "Init"
        const val PUSH_TAG = "Push"
    }

    fun coreSdkInitStart() {
        logger.debug(
            tag = INIT_TAG,
            message = "Creating new instance of CustomerIO SDK version: ${Version.version}..."
        )
    }

    fun coreSdkAlreadyInitialized() {
        logger.error(
            tag = INIT_TAG,
            message = "CustomerIO instance is already initialized, skipping the initialization",
            throwable = IllegalStateException("CustomerIO SDK already initialized")
        )
    }

    fun coreSdkInitSuccess() {
        logger.info(
            tag = INIT_TAG,
            message = "CustomerIO SDK is initialized and ready to use"
        )
    }

    fun moduleInitStart(module: CustomerIOModule<out CustomerIOModuleConfig>) {
        logger.debug(
            tag = INIT_TAG,
            message = "Initializing SDK module ${module.moduleName} with config: ${module.moduleConfig}..."
        )
    }

    fun moduleInitSuccess(module: CustomerIOModule<out CustomerIOModuleConfig>) {
        logger.info(
            tag = INIT_TAG,
            message = "CustomerIO ${module.moduleName} module is initialized and ready to use"
        )
    }

    /**
     * The SDK's current identity, token and key in one line that tools (cio-sdk-tools `verify push`)
     * read; the latest line wins. The user id and token are already logged at debug elsewhere.
     */
    fun logSdkState(userId: String?, token: String?, cdpApiKey: String) {
        logger.debug(
            tag = INIT_TAG,
            message = "SDK state" + LogTail.build(
                "sdk.state",
                "version" to Version.version,
                "user" to (userId?.takeIf { it.isNotEmpty() } ?: "anonymous"),
                "token" to (token?.takeIf { it.isNotEmpty() } ?: "none"),
                // Enough to tell workspaces apart; never the whole key
                "key" to cdpApiKey.take(8).ifEmpty { "none" }
            )
        )
    }

    //region Push
    fun logStoringDevicePushToken(token: String, userId: String?) {
        logger.debug(
            tag = PUSH_TAG,
            message = "Storing device token: $token for user profile: $userId"
        )
    }

    fun logStoringBlankPushToken() {
        logger.debug(
            tag = PUSH_TAG,
            message = "Attempting to register blank token, ignoring request"
        )
    }

    fun logRegisteringPushToken(token: String, userId: String?) {
        logger.debug(
            tag = PUSH_TAG,
            message = "Registering device token: $token for user profile: $userId"
        )
    }

    fun logPushTokenRefreshed() {
        logger.debug(
            tag = PUSH_TAG,
            message = "Token refreshed, deleting old token to avoid registering same device multiple times"
        )
    }

    fun automaticTokenRegistrationForNewProfile(token: String, userId: String) {
        logger.debug(
            tag = PUSH_TAG,
            message = "Automatically registering device token: $token to newly identified profile: $userId"
        )
    }

    fun logDeletingTokenDueToNewProfileIdentification() {
        logger.debug(
            tag = PUSH_TAG,
            message = "Deleting device token before identifying new profile"
        )
    }

    fun logTrackingDevicesAttributesWithoutValidToken() {
        logger.debug(
            tag = PUSH_TAG,
            message = "No device token found. ignoring request to track device attributes"
        )
    }
    //endregion Push
}

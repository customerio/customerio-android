package io.customer.messaginginapp

import io.customer.sdk.module.CustomerIOModuleConfig

/**
 * In app messaging module configurations that can be used to customize app
 * experience based on the provided configurations
 */
class MessagingInAppModuleConfig private constructor() : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<MessagingInAppModuleConfig> {
        override fun build(): MessagingInAppModuleConfig {
            return MessagingInAppModuleConfig()
        }
    }

    companion object {
        internal fun default(): MessagingInAppModuleConfig = Builder().build()
    }
}

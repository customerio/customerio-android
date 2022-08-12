package io.customer.sdk.module

/**
 * Tagged interface to support dynamic configurations for Customer.io modules
 * <p/>
 * Child class must implement [Builder] to enforce builder pattern for
 * constructing configurations.
 */
interface CustomerIOModuleConfig {
    /**
     * Basic interface to implement builder pattern for module configurations
     *
     * @param Config generic type of configurations required by the module
     */
    interface Builder<out Config : CustomerIOModuleConfig> {
        fun build(): Config
    }
}

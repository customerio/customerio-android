package io.customer.commontest.config

typealias ConfigDSL<Config> = Config.() -> Unit

operator fun <Config> ConfigDSL<Config>.plus(other: ConfigDSL<Config>): ConfigDSL<Config> = {
    this@plus()
    other()
}

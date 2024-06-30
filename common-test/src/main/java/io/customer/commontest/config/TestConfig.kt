package io.customer.commontest.config

interface TestConfig {
    val diGraph: DIGraphConfiguration
    operator fun plus(other: TestConfig): TestConfig
}

abstract class TestConfigBuilder<Config : TestConfig> {
    abstract fun build(): Config

    protected var diGraphConfiguration: DIGraphConfiguration = DIGraphConfiguration()

    fun diGraph(block: ConfigDSL<DIGraphConfiguration>) {
        diGraphConfiguration = DIGraphConfiguration().apply(block)
    }
}

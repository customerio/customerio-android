package io.customer.commontest.config

class DefaultTestConfig internal constructor(
    override val diGraph: DIGraphConfiguration
) : TestConfig {
    override fun plus(other: TestConfig): TestConfig {
        return DefaultTestConfig(diGraph = diGraph + other.diGraph)
    }

    class Builder : TestConfigBuilder<DefaultTestConfig>() {
        override fun build(): DefaultTestConfig = DefaultTestConfig(diGraph = diGraphConfiguration)
    }
}

fun testConfigurationDefault(block: DefaultTestConfig.Builder.() -> Unit): TestConfig {
    return DefaultTestConfig.Builder().apply(block).build()
}

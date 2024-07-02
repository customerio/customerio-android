package io.customer.commontest.config

/**
 * Default implementation of [TestConfig] that contains a list of test arguments
 * and DI graph configuration.
 * The configuration can be combined with other configurations using the [plus] operator.
 */
class DefaultTestConfig internal constructor(
    override val arguments: List<TestArgument>,
    override val diGraph: DIGraphConfiguration
) : TestConfig {
    override fun plus(other: TestConfig): TestConfig {
        return DefaultTestConfig(
            arguments = arguments + other.arguments,
            diGraph = diGraph + other.diGraph
        )
    }

    class Builder : TestConfigBuilder<DefaultTestConfig>() {
        override fun build(): DefaultTestConfig = DefaultTestConfig(
            arguments = arguments,
            diGraph = diGraphConfiguration
        )
    }
}

/**
 * Creates a new [DefaultTestConfig] using the provided DSL.
 */
fun testConfigurationDefault(block: DefaultTestConfig.Builder.() -> Unit): TestConfig {
    return DefaultTestConfig.Builder().apply(block).build()
}

package io.customer.commontest.config

/**
 * Base interface for all test configurations.
 * A test configuration is a collection of test arguments and a DI graph configuration.
 * This allows for easy configuration of test environments and dependencies.
 * The configuration can be combined with other configurations using the [plus] operator
 * to create a new configuration that contains all the arguments and DI graph configurations of both.
 */
interface TestConfig {
    val arguments: List<TestArgument>
    val diGraph: DIGraphConfiguration

    /**
     * Combines this configuration with another configuration to create a new configuration.
     * The new configuration contains all the arguments and DI graph configurations of both configurations.
     * Configuration values from the other configuration take precedence over this configuration.
     * So in general, child configurations should be passed as the other configuration to override parent configurations.
     * However, in case of DSL configurations, the parent configuration is called first to mimic the behavior of overriding.
     */
    operator fun plus(other: TestConfig): TestConfig
}

/**
 * Builder class for creating test configurations using a DSL.
 * The builder is responsible for creating a new configuration instance with the provided arguments
 * using DSL functions and returning configuration instance.
 */
abstract class TestConfigBuilder<Config : TestConfig> {
    abstract fun build(): Config

    protected var arguments: MutableList<TestArgument> = mutableListOf()
    protected var diGraphConfiguration: DIGraphConfiguration = DIGraphConfiguration()

    fun argument(value: TestArgument) {
        arguments.add(value)
    }

    fun diGraph(block: ConfigDSL<DIGraphConfiguration>) {
        diGraphConfiguration = DIGraphConfiguration().apply(block)
    }
}

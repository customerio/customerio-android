package io.customer.commontest.config

interface TestConfig {
    val arguments: List<TestArgument>
    val diGraph: DIGraphConfiguration
    operator fun plus(other: TestConfig): TestConfig
}

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

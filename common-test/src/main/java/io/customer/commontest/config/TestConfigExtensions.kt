package io.customer.commontest.config

import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent

/**
 * Typealias for test configuration DSL for improved readability and ease of use.
 */
typealias BaseDSL<Input, Output> = Input.() -> Output
typealias ConfigDSL<Config> = BaseDSL<Config, Unit>
typealias AnalyticsDSL<Config> = BaseDSL<Config, Config>

/**
 * Combines two [BaseDSL]s into a single [BaseDSL] that will execute both of them in sequence.
 * This is useful for combining multiple configurations into a single configuration
 * and mimics the behavior of overriding method in a class.
 */
operator fun <Input, Output> BaseDSL<Input, Output>.plus(other: BaseDSL<Input, Output>): BaseDSL<Input, Output> = {
    this@plus()
    other()
}

/**
 * Retrieves the first argument of type [Arg] from test configuration arguments.
 * If no argument of type [Arg] is found, returns null.
 * If more than one argument of type [Arg] is found, throws an [IllegalArgumentException].
 */
inline fun <reified Arg : TestArgument> TestConfig.argumentOrNull(): Arg? {
    return arguments.filterIsInstance<Arg>().singleOrNull()
}

/**
 * Retrieves the first argument of type [Arg] from test configuration arguments.
 * If no argument of type [Arg] is found, throws an [IllegalArgumentException].
 * If more than one argument of type [Arg] is found, throws an [IllegalArgumentException].
 */
inline fun <reified Arg : TestArgument> TestConfig.argument(): Arg {
    return argumentOrNull<Arg>() ?: throw IllegalArgumentException("Expected one argument of type ${Arg::class.java.simpleName}, but found none")
}

/**
 * Configures the SDK component in the test configuration with the given instance.
 * If no instance is provided, the default instance is used.
 */
fun TestConfig.configureSDKComponent(instance: SDKComponent = SDKComponent) {
    diGraph.sdkComponent.invoke(instance)
}

/**
 * Configures the Android SDK component in the test configuration with the given instance.
 * If no instance is provided, the default instance is used.
 */
fun TestConfig.configureAndroidSDKComponent(instance: AndroidSDKComponent = SDKComponent.android()) {
    diGraph.androidSDKComponent.invoke(instance)
}

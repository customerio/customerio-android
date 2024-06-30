package io.customer.commontest.config

typealias ConfigDSL<Config> = Config.() -> Unit

operator fun <Config> ConfigDSL<Config>.plus(other: ConfigDSL<Config>): ConfigDSL<Config> = {
    this@plus()
    other()
}

inline fun <reified Arg : TestArgument> TestConfig.argumentOrNull(): Arg? {
    val args = arguments.filterIsInstance<Arg>()
    return when {
        args.isEmpty() -> return null
        args.size == 1 -> args[0]
        else -> throw IllegalArgumentException("Expected exactly one argument of type ${Arg::class.java.simpleName}, but found ${args.size}")
    }
}

inline fun <reified Arg : TestArgument> TestConfig.argument(): Arg {
    return argumentOrNull<Arg>() ?: throw IllegalArgumentException("Expected one argument of type ${Arg::class.java.simpleName}, but found none")
}

package io.customer.commontest.extensions

import io.customer.sdk.core.di.DiGraph

inline fun <reified Dependency : Any> DiGraph.overrideDependency(
    value: Dependency
) = overrideDependency(Dependency::class.java, value)

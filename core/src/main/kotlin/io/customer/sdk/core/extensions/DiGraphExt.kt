package io.customer.sdk.core.extensions

import io.customer.sdk.core.di.DiGraph

/**
 * Extension function to allow getting a dependency from the graph by an enum key.
 * Enums make it easier to utilize keys by avoiding typos and copy-paste errors.
 */
inline fun <reified Dependency : Any, reified Key : Enum<Key>> DiGraph.getOrNull(identifier: Key): Dependency? {
    return getOrNull(identifier = identifier.name)
}

/**
 * Extension function to allow registering a dependency in the graph by an enum key.
 * Enums make it easier to utilize keys by avoiding typos and copy-paste errors.
 */
inline fun <reified Dependency : Any, reified Key : Enum<Key>> DiGraph.registerDependency(
    identifier: Key,
    newInstanceCreator: () -> Dependency
) = registerDependency(
    identifier = identifier.name,
    newInstanceCreator = newInstanceCreator
)

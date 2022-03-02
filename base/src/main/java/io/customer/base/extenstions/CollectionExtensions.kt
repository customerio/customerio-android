package io.customer.base.extenstions

/**
 * Like [Collection.map], but you return the first non-null
 * item instead of returning a [List] of items.
 */
fun <T, R> Collection<T>.mapFirst(block: (T) -> R?): R? {
    this.forEach { item ->
        val result = block(item)
        if (result != null) return result
    }

    return null
}

/**
 * Like [mapFirst] but a suspend version.
 */
suspend fun <T, R> Collection<T>.mapFirstSuspend(block: suspend (T) -> R?): R? {
    this.forEach { item ->
        val result = block(item)
        if (result != null) return result
    }

    return null
}

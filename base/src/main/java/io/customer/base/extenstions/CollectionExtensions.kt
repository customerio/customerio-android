package io.customer.base.extenstions

fun <E> Collection<E>.containsAny(from: Collection<E>): Boolean {
    from.forEach { itemFrom ->
        if (this.contains(itemFrom)) return true
    }

    return false
}

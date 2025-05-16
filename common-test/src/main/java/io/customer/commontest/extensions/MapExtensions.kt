package io.customer.commontest.extensions

@Suppress("UNCHECKED_CAST")
fun <K, V : Any> Map<K, V?>.filterNotNullValues(): Map<K, V> = this.filterValues {
    it != null
} as Map<K, V>

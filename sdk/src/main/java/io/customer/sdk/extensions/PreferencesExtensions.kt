package io.customer.sdk.extensions

import android.content.SharedPreferences
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.extenstions.unixTimeToDate
import java.util.*

fun SharedPreferences.Editor.putDate(key: String, value: Date?) {
    val newValue = value?.getUnixTimestamp() ?: Long.MIN_VALUE
    putLong(key, newValue)
}

fun SharedPreferences.getDate(key: String): Date? {
    getLong(key, Long.MIN_VALUE).let {
        return if (it == Long.MIN_VALUE) null else it.unixTimeToDate()
    }
}

package io.customer.messagingpush.extensions

import android.content.res.Resources
import android.os.Build
import android.os.Bundle

private val RESOURCE_ID_NULL: Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Resources.ID_NULL else 0

internal fun Bundle.getMetaDataResource(name: String): Int? {
    return getInt(name, RESOURCE_ID_NULL).takeUnless { id -> id == RESOURCE_ID_NULL }
}

internal fun Bundle.getMetaDataString(name: String): String? {
    return getString(name, null).takeUnless { value -> value.isNullOrBlank() }
}

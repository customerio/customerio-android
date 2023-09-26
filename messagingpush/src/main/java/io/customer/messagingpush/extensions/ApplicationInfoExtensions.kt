package io.customer.messagingpush.extensions

import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

private val RESOURCE_ID_NULL: Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Resources.ID_NULL else 0

internal fun Bundle.getMetaDataResource(name: String): Int? {
    return getInt(name, RESOURCE_ID_NULL).takeUnless { id -> id == RESOURCE_ID_NULL }
}

internal fun Bundle.getMetaDataString(name: String): String? {
    return getString(name, null).takeUnless { value -> value.isNullOrBlank() }
}

inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? = when {
    // There is a known bug on Android 13 which can throw NPE on newly added getParcelable method
    // The issue is fixed for the next major Android release, but can't be back-ported to Android 13
    // The recommended approach is to continue using the older APIs for Android 13 and below
    // See following issue for more details
    // https://issuetracker.google.com/issues/240585930#comment6
    Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU -> getParcelable(key, T::class.java)
    else -> @Suppress("DEPRECATION")
    getParcelable(key) as? T
}

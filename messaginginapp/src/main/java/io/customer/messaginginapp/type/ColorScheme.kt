package io.customer.messaginginapp.type

import android.content.res.Configuration

enum class ColorScheme {
    LIGHT,
    DARK,
    AUTO;

    internal fun resolve(uiMode: Int): String {
        return when (this) {
            LIGHT -> RENDERER_LIGHT
            DARK -> RENDERER_DARK
            AUTO -> {
                val nightMode = uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == Configuration.UI_MODE_NIGHT_YES) RENDERER_DARK else RENDERER_LIGHT
            }
        }
    }

    internal companion object {
        const val RENDERER_LIGHT = "light"
        const val RENDERER_DARK = "dark"
    }
}

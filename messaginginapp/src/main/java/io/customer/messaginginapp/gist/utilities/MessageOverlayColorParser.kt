package io.customer.messaginginapp.gist.utilities

internal object MessageOverlayColorParser {

    /**
     * The expected color is formatted as #RRGGBBAA with alpha channel at the end, we need
     * to reformat it to be #AARRGGBB to be usable on Android
     */
    fun parseColor(color: String?): String? {
        if (color == null) {
            return null
        }

        val cleanColor = color.removePrefix("#")

        if (doesNotHaveExpectedColorCharCount(cleanColor)) {
            return null
        }

        val red = cleanColor.substring(0, 2)
        val green = cleanColor.substring(2, 4)
        val blue = cleanColor.substring(4, 6)
        val alpha = if (cleanColor.length == 8) cleanColor.substring(6, 8) else ""

        return "#$alpha$red$green$blue"
    }

    private fun doesNotHaveExpectedColorCharCount(color: String): Boolean {
        return color.length != 6 && color.length != 8
    }
}

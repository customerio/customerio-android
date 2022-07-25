package io.customer.sdk.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

interface DeepLinkUtil {
    fun createIntentsForLink(context: Context, deepLink: String?): List<Intent>
}

class DeepLinkUtilImpl : DeepLinkUtil {
    override fun createIntentsForLink(context: Context, deepLink: String?): List<Intent> {
        val intent = deepLink?.let { link -> resolveDeepLinkIntent(context, link) }
        return if (intent == null) {
            Log.v(
                TAG,
                "No supporting application for this deepLink $deepLink," +
                    " launching default activity"
            )
            listOfNotNull(getDefaultLaunchIntent(context))
        } else listOfNotNull(intent)
    }

    private fun resolveDeepLinkIntent(context: Context, deepLink: String): Intent? {
        // check if the deep links are handled within the host app
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
        intent.setPackage(context.packageName)
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            flags = flags or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER
        }
        intent.flags = flags

        // Since we are looking for activities only within the app, we don't really need
        // queries in the manifest
        @SuppressLint("QueryPermissionsNeeded")
        val isActivityFound = intent.resolveActivity(context.packageManager) != null
        return if (isActivityFound) intent else null
    }

    private fun getDefaultLaunchIntent(context: Context): Intent? {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)
    }

    companion object {
        private const val TAG = "DeepLinkUtil:"
    }
}

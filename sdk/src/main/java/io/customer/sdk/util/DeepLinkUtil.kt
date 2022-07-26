package io.customer.sdk.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.TaskStackBuilder

interface DeepLinkUtil {
    /**
     * Creates list of intents for the provided link for the provided link.
     *
     * @param context reference to application context
     * @param deepLink link to create intent for
     * @return list of intents to add in [TaskStackBuilder] for the provided
     * link, empty if no matching intent found
     */
    fun createDefaultDeepLinkHandlerIntents(context: Context, deepLink: String?): List<Intent>?
}

class DeepLinkUtilImpl(
    private val logger: Logger
) : DeepLinkUtil {
    override fun createDefaultDeepLinkHandlerIntents(
        context: Context,
        deepLink: String?
    ): List<Intent>? {
        val intent = deepLink?.let { link -> resolveDeepLinkIntent(context, link) }
        if (intent != null) return listOf(intent)

        logger.info(
            "No supporting application for this deepLink $deepLink," +
                " launching default activity"
        )
        return null
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
}

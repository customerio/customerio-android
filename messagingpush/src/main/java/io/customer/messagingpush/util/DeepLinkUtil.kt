package io.customer.messagingpush.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Patterns
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.sdk.util.Logger

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
    private val moduleConfig: MessagingPushModuleConfig,
    private val logger: Logger
) : DeepLinkUtil {
    private val notificationIntentFlags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    override fun createDefaultDeepLinkHandlerIntents(
        context: Context,
        deepLink: String?
    ): List<Intent>? {
        if (deepLink.isNullOrBlank()) {
            logger.debug("No link received in push notification content")
            return null
        }

        var intent: Intent? = null
        val linkUri = Uri.parse(deepLink)

        queryDeepLinksForHostApp(context, linkUri)
        if (intent == null && moduleConfig.redirectDeepLinksToThirdPartyApps) {
            intent = queryDeepLinksForThirdPartyApps(context, linkUri)
        }
        if (intent == null && moduleConfig.redirectDeepLinksToHttpBrowser) {
            intent = queryDeepLinksForBrowser(context, linkUri)
        }

        if (intent == null) {
            logger.info(
                "No supporting application found for link received in push " +
                    "notification $deepLink"
            )
        }
        return listOfNotNull(intent)
    }

    private fun queryDeepLinksForHostApp(context: Context, uri: Uri): Intent? {
        // check if the deep link is handled within the host app
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage(context.packageName)

        intent.flags = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) notificationIntentFlags
        else notificationIntentFlags or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER

        return intent.takeIf { component -> component.resolveActivity(context.packageManager) != null }
    }

    private fun queryDeepLinksForThirdPartyApps(
        context: Context,
        uri: Uri
    ): Intent? {
        // check if the deep link is handled by any app outside the host app
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.flags = notificationIntentFlags

        val resolveInfo = context.packageManager.queryIntentActivities(intent, 0)
        resolveInfo.firstOrNull()?.let { item ->
            intent.setPackage(item.activityInfo.packageName)
        }

        return intent.takeIf { component -> component.resolveActivity(context.packageManager) != null }
    }

    private fun queryDeepLinksForBrowser(context: Context, uri: Uri): Intent? {
        // check if the deep link is valid browser url
        if (!Patterns.WEB_URL.matcher(uri.toString()).matches()) {
            return null
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.flags = notificationIntentFlags
        return intent.takeIf { component -> component.resolveActivity(context.packageManager) != null }
    }
}

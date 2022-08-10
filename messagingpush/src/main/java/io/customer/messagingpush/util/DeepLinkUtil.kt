package io.customer.messagingpush.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.lifecycle.MessagingPushLifecycleCallback
import io.customer.sdk.util.Logger

interface DeepLinkUtil {
    /**
     * Creates default launcher intent for host app.
     *
     * @param context reference to application context
     * @param contentActionLink action link to add to extras so it can be
     * opened after launcher activity has been created. This helps opening
     * external links without affecting open metrics on Android 12 and
     * onwards.
     * @return launcher intent for host app; null if fail to resolve
     * default launcher intent
     */
    fun createDefaultHostAppIntent(context: Context, contentActionLink: String?): Intent?

    /**
     * Creates intent from host app activities matching the provided link.
     *
     * @param context reference to application context
     * @param link link to create intent for
     * @return intent matching the link in traditional Android way; null if no
     * matching intents found
     */
    fun createDeepLinkHostAppIntent(context: Context, link: String?): Intent?

    /**
     * Creates intent outside the host app that can open the provided link.
     *
     * @param context reference to application context
     * @param link link to create intent for
     * @param startingFromService flag to indicate if the intent is to be
     * started from service so required flags can be added
     * @return intent that can open the link outside the host app; null if no
     * matching intent found
     */
    fun createDeepLinkExternalIntent(
        context: Context,
        link: String,
        startingFromService: Boolean
    ): Intent?
}

class DeepLinkUtilImpl(
    private val logger: Logger,
    private val moduleConfig: MessagingPushModuleConfig
) : DeepLinkUtil {
    private val notificationIntentFlags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    override fun createDefaultHostAppIntent(context: Context, contentActionLink: String?): Intent? {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            // Add pending link to open outside host app so open tracking metrics are not affected
            putExtra(MessagingPushLifecycleCallback.PENDING_CONTENT_ACTION_LINK, contentActionLink)
        }
    }

    override fun createDeepLinkHostAppIntent(context: Context, link: String?): Intent? {
        if (link.isNullOrBlank()) {
            logger.debug("No link received in push notification content")
            return null
        }

        val intent: Intent? = queryDeepLinksForHostApp(context, Uri.parse(link))
        return if (intent != null) intent
        else {
            logger.info(
                "No supporting activity found in host app for link received in" +
                    " push notification $link"
            )
            null
        }
    }

    override fun createDeepLinkExternalIntent(
        context: Context,
        link: String,
        startingFromService: Boolean
    ): Intent? {
        val linkUri = Uri.parse(link)
        var intent: Intent? = null

        if (moduleConfig.redirectDeepLinksToOtherApps) {
            intent = queryDeepLinksForThirdPartyApps(
                context = context,
                uri = linkUri,
                startingFromService = startingFromService
            )

            if (intent == null) {
                logger.info(
                    "No supporting application found for link received in " +
                        "push notification: $link"
                )
            }
        }

        return intent
    }

    private fun Intent.takeIfResolvable(packageManager: PackageManager): Intent? {
        return takeIf { intent ->
            intent.resolveActivity(packageManager) != null
        }
    }

    private fun queryDeepLinksForHostApp(context: Context, uri: Uri): Intent? {
        // check if the deep link is handled within the host app
        val hostAppIntent = Intent(Intent.ACTION_VIEW, uri)
        hostAppIntent.setPackage(context.packageName)

        hostAppIntent.flags =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) notificationIntentFlags
            else notificationIntentFlags or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER

        return hostAppIntent.takeIfResolvable(context.packageManager)
    }

    private fun queryDeepLinksForThirdPartyApps(
        context: Context,
        uri: Uri,
        startingFromService: Boolean
    ): Intent? {
        // check if the deep link can be opened by any other app
        val browsableIntent = Intent(Intent.ACTION_VIEW, uri)
        if (startingFromService) {
            browsableIntent.flags = notificationIntentFlags
        }

        val resolveInfo = context.packageManager.queryIntentActivities(browsableIntent, 0)
        if (resolveInfo.isNotEmpty()) {
            browsableIntent.setPackage(resolveInfo.first().activityInfo.packageName)
        }

        return browsableIntent.takeIfResolvable(context.packageManager)
    }
}

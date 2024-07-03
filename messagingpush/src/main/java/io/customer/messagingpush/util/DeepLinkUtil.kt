package io.customer.messagingpush.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.sdk.core.util.Logger

interface DeepLinkUtil {
    /**
     * Creates default launcher intent for host app.
     *
     * @param context reference to application context
     * @return launcher intent for host app; null if fail to resolve
     * default launcher intent
     */
    fun createDefaultHostAppIntent(context: Context): Intent?

    /**
     * Creates intent from host app activities matching the provided link.
     *
     * @param context reference to application context
     * @param link link to create intent for
     * @return intent matching the link in traditional Android way; null if no
     * matching intents found
     */
    fun createDeepLinkHostAppIntent(context: Context, link: String): Intent?

    /**
     * Creates intent outside the host app that can open the provided link.
     *
     * @param context reference to application context
     * @param link link to create intent for
     * @return intent that can open the link outside the host app; null if no
     * matching intent found
     */
    fun createDeepLinkExternalIntent(
        context: Context,
        link: String
    ): Intent?
}

class DeepLinkUtilImpl(
    private val logger: Logger,
    private val moduleConfig: MessagingPushModuleConfig
) : DeepLinkUtil {
    override fun createDefaultHostAppIntent(context: Context): Intent? {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)
    }

    override fun createDeepLinkHostAppIntent(context: Context, link: String): Intent? {
        val intent: Intent? = queryDeepLinksForHostApp(context, Uri.parse(link))
        if (intent == null) {
            logger.info(
                "No supporting activity found in host app for link received in push notification $link"
            )
        }
        return intent
    }

    override fun createDeepLinkExternalIntent(context: Context, link: String): Intent? {
        val linkUri = Uri.parse(link)
        val intent = queryDeepLinksForThirdPartyApps(context = context, uri = linkUri)
        if (intent == null) {
            logger.info(
                "No supporting application found for link received in " +
                    "push notification: $link"
            )
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
        return hostAppIntent.takeIfResolvable(context.packageManager)
    }

    private fun queryDeepLinksForThirdPartyApps(
        context: Context,
        uri: Uri
    ): Intent? {
        // check if the deep link can be opened by any other app
        val browsableIntent = Intent(Intent.ACTION_VIEW, uri)
        val packageManager = context.packageManager
        val resolveInfoFlag = PackageManager.MATCH_DEFAULT_ONLY

        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                browsableIntent,
                PackageManager.ResolveInfoFlags.of(resolveInfoFlag.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(browsableIntent, resolveInfoFlag)
        }
        if (resolveInfo.isNotEmpty()) {
            browsableIntent.setPackage(resolveInfo.first().activityInfo.packageName)
        }

        return browsableIntent.takeIfResolvable(packageManager)
    }
}

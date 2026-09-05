package io.customer.messagingpush.livenotification

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONObject

/**
 * Persists [LiveNotificationBranding] so a cold process — one Android started solely to deliver an
 * FCM message, where `CustomerIO.initialize` never ran — renders with the same branding a warm
 * process would use. Without it a cold render silently falls back to the app's manifest icon and no
 * logo, visibly changing an ongoing notification halfway through its life.
 *
 * Resource **names** are stored rather than resource ids: ids are assigned at build time and are not
 * stable across app updates, so a persisted id can resolve to an unrelated drawable after an
 * upgrade. The resource *type* is stored alongside the name because a small icon is frequently a
 * `mipmap` (app launcher icons) rather than a `drawable`, and it is resolved against the *current*
 * package at decode time so a persisted entry survives any package-name change.
 *
 * Neither direction throws: anything unrepresentable or unresolvable degrades to "no branding" for
 * that field, because failing a render is strictly worse than rendering it unbranded.
 */
internal object LiveNotificationBrandingSerializer {

    fun encode(context: Context, branding: LiveNotificationBranding): String? = runCatching {
        JSONObject().apply {
            put(KEY_COMPANY_NAME, branding.companyName)
            put(KEY_ACCENT_COLOR, branding.accentColor)
            branding.smallIcon
                ?.let { resourceToJson(context, it) }
                ?.let { put(KEY_SMALL_ICON, it) }
            encodeLogo(context, branding.logo)?.let { put(KEY_LOGO, it) }
        }.toString()
    }.getOrElse { cause ->
        SDKComponent.logger.error(
            "Failed to persist live notification branding: ${cause.message}"
        )
        null
    }

    fun decode(context: Context, json: String): LiveNotificationBranding? = runCatching {
        val root = JSONObject(json)
        LiveNotificationBranding(
            companyName = root.optString(KEY_COMPANY_NAME),
            accentColor = root.getInt(KEY_ACCENT_COLOR),
            smallIcon = root.optJSONObject(KEY_SMALL_ICON)?.let { resourceFromJson(context, it) },
            logo = root.optJSONObject(KEY_LOGO)?.let { decodeLogo(context, it) }
        )
    }.getOrElse { cause ->
        SDKComponent.logger.error(
            "Failed to read persisted live notification branding; rendering unbranded: ${cause.message}"
        )
        null
    }

    private fun encodeLogo(context: Context, logo: LiveNotificationAsset?): JSONObject? = when (logo) {
        null -> null
        is LiveNotificationAsset.Drawable -> resourceToJson(context, logo.resId)
            ?.put(KEY_LOGO_KIND, LOGO_DRAWABLE)

        is LiveNotificationAsset.RemoteUrl -> JSONObject()
            .put(KEY_LOGO_KIND, LOGO_REMOTE_URL)
            .put(KEY_LOGO_VALUE, logo.url)

        is LiveNotificationAsset.Resource -> JSONObject()
            .put(KEY_LOGO_KIND, LOGO_RESOURCE_URI)
            .put(KEY_LOGO_VALUE, logo.uri.toString())

        // Raw bytes are unbounded, and SharedPreferences is the wrong place for an image payload.
        // A cold render simply has no logo; every other branding field still applies.
        is LiveNotificationAsset.Bytes -> {
            SDKComponent.logger.debug(
                "Live notification branding logo is raw bytes; it will not be available after " +
                    "process death. Use a bundled drawable or a remote URL to keep it."
            )
            null
        }
    }

    private fun decodeLogo(context: Context, json: JSONObject): LiveNotificationAsset? =
        when (val kind = json.optString(KEY_LOGO_KIND)) {
            LOGO_DRAWABLE -> resourceFromJson(context, json)?.let(LiveNotificationAsset::Drawable)
            LOGO_REMOTE_URL -> json.optString(KEY_LOGO_VALUE)
                .takeIf { it.isNotBlank() }
                ?.let(LiveNotificationAsset::RemoteUrl)

            LOGO_RESOURCE_URI -> json.optString(KEY_LOGO_VALUE)
                .takeIf { it.isNotBlank() }
                ?.let { LiveNotificationAsset.Resource(Uri.parse(it)) }

            else -> {
                SDKComponent.logger.debug("Unknown persisted live notification logo kind '$kind'; ignoring.")
                null
            }
        }

    /**
     * `resId` -> `{package, type, entry}`, or null when the id doesn't resolve to a named resource.
     *
     * The package is recorded because branding may legitimately point at a resource outside the
     * app's own package (a framework or library drawable), and the type because a small icon is
     * frequently a `mipmap` (launcher icons) rather than a `drawable`.
     */
    private fun resourceToJson(context: Context, resId: Int): JSONObject? = runCatching {
        JSONObject()
            .put(KEY_RES_PKG, context.resources.getResourcePackageName(resId))
            .put(KEY_RES_TYPE, context.resources.getResourceTypeName(resId))
            .put(KEY_RES_ENTRY, context.resources.getResourceEntryName(resId))
    }.getOrElse { cause ->
        SDKComponent.logger.debug(
            "Live notification branding resource $resId has no resolvable name; not persisting it: ${cause.message}"
        )
        null
    }

    /**
     * `{package, type, entry}` -> `resId`, or null when the resource no longer resolves (renamed or
     * removed by an app update since it was persisted).
     */
    @SuppressLint("DiscouragedApi")
    private fun resourceFromJson(context: Context, json: JSONObject): Int? {
        val type = json.optString(KEY_RES_TYPE).takeIf { it.isNotBlank() } ?: return null
        val entry = json.optString(KEY_RES_ENTRY).takeIf { it.isNotBlank() } ?: return null
        // Try the package the resource was compiled into first — branding may point at a framework
        // or library resource, not an app one — then the app's current package, which covers both an
        // entry persisted before the package was recorded and an app whose package name changed.
        val packages = listOfNotNull(
            json.optString(KEY_RES_PKG).takeIf { it.isNotBlank() },
            context.packageName
        ).distinct()
        // getIdentifier is discouraged, but resolving a persisted name is exactly what it is for —
        // the same mechanism Context.getDrawableByName already uses for push payload icons. It
        // documents 0 (not Resources.ID_NULL, which is API 29+) for "no such resource".
        return packages.firstNotNullOfOrNull { pkg ->
            context.resources.getIdentifier(entry, type, pkg).takeUnless { it == 0 }
        }
    }

    private const val KEY_COMPANY_NAME = "companyName"
    private const val KEY_ACCENT_COLOR = "accentColor"
    private const val KEY_SMALL_ICON = "smallIcon"
    private const val KEY_LOGO = "logo"

    private const val KEY_RES_PKG = "resPkg"
    private const val KEY_RES_TYPE = "resType"
    private const val KEY_RES_ENTRY = "resEntry"

    private const val KEY_LOGO_KIND = "kind"
    private const val KEY_LOGO_VALUE = "value"

    private const val LOGO_DRAWABLE = "drawable"
    private const val LOGO_REMOTE_URL = "remoteUrl"
    private const val LOGO_RESOURCE_URI = "resourceUri"
}

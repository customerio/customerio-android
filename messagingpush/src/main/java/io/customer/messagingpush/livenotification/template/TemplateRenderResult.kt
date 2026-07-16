package io.customer.messagingpush.livenotification.template

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * Normalized render output produced by every [LiveNotificationTemplate]; the
 * handler converts it into the API-36 or basic notification params. Fields with
 * no pre-API-36 counterpart are ignored on lower tiers.
 */
internal data class TemplateRenderResult(
    val title: String,
    val body: String,
    val subText: String?,
    val largeIcon: Bitmap?,
    @ColorInt val accentColor: Int?,
    val colorized: Boolean,
    val showProgress: Boolean,
    val progress: Int,
    val progressMax: Int,
    val segments: List<SegmentSpec>,
    val points: List<PointSpec>,
    @DrawableRes val startIconRes: Int?,
    @DrawableRes val endIconRes: Int?,
    @DrawableRes val trackerIconRes: Int?,
    val countdownUntil: Long?,
    val deepLink: String?,
    val cancelImmediately: Boolean = false
)

internal data class SegmentSpec(
    val length: Int,
    @ColorInt val color: Int? = null
)

internal data class PointSpec(
    val position: Int,
    @ColorInt val color: Int? = null
)

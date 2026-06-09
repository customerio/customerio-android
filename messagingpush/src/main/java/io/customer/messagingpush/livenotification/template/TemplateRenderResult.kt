package io.customer.messagingpush.livenotification.template

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * Normalized intermediate produced by every [LiveNotificationTemplate].
 *
 * The handler converts this into either [io.customer.messagingpush.Api36LiveNotificationParams]
 * or [io.customer.messagingpush.BasicNotificationParams] depending on the device's
 * API level — so this struct is the union of both target shapes.
 *
 * Fields that have no counterpart on the lower tier (segments, points, progress
 * icons) are silently ignored on pre-API-36 builds.
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

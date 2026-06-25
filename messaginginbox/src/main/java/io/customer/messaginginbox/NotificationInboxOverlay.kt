package io.customer.messaginginbox

import android.util.TypedValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.customer.jist.JistActionEvent
import io.customer.jist.JistMode
import io.customer.jist.JistView
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.Branding
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import io.customer.sdk.core.di.SDKComponent
import kotlinx.serialization.json.JsonObject

/**
 * Floating notification **bell** (with unread badge) you can place anywhere in your Compose UI —
 * e.g. a top app bar, a tab, or a corner. The bell appears only when the inbox has something to
 * show and updates reactively as messages arrive or are read; it renders nothing otherwise.
 *
 * Pair it with [NotificationInboxView] (which you present however you like — a sheet, a screen, a
 * popup) by toggling your own visibility state from [onClick]. For the ready-made floating bell +
 * slide-out panel, use [NotificationInboxOverlay] instead.
 *
 * @param onClick invoked when the user taps the bell (e.g. to show/hide your inbox view).
 * @param modifier Modifier applied to the bell.
 */
@Composable
fun NotificationInboxBell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controller = rememberInboxController()
    val state by remember(controller) { controller.uiStateFlow() }
        .collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))

    // Only show the bell when the inbox is renderable: enabled+Visible AND at least one message has a
    // template to render (a message whose type has no template is skipped by the list, so if none can
    // render there is nothing to show).
    val hasRenderableMessages = rememberHasRenderableMessages(state)
    if (!state.isVisible || !hasRenderableMessages) return

    InboxBellContent(
        unopenedCount = state.unopenedCount,
        colors = rememberInboxColors((state.visibility as? InboxVisibility.Visible)?.branding),
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * The Visual Notification Inbox **message list** — the Jist-rendered messages — that you can embed
 * directly in your own screen (or present in a sheet/dialog). It fills the [modifier] you give it
 * and brings no surrounding chrome (no card/scrim), so you control the placement and container.
 *
 * Showing this view marks the currently-unread messages as opened (mirroring the panel-open
 * behavior); tapping a message dismisses it or runs its action. For the ready-made floating bell +
 * slide-out panel, use [NotificationInboxOverlay]; to drive your own bell, use [NotificationInboxBell].
 *
 * @param modifier Modifier applied to the list container.
 */
@Composable
fun NotificationInboxView(
    modifier: Modifier = Modifier
) {
    val controller = rememberInboxController()
    // Collect state for branding-driven chrome colors (the bell/overlay do the same); InboxListContent
    // collects its own state for the message list.
    val state by remember(controller) { controller.uiStateFlow() }
        .collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))
    InboxListContent(
        controller = controller,
        colors = rememberInboxColors((state.visibility as? InboxVisibility.Visible)?.branding),
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Drop-in Compose overlay that shows the Customer.io Visual Notification Inbox on top of your app:
 * a floating [NotificationInboxBell] pinned bottom-end that slides out a [NotificationInboxView]
 * panel; tapping outside the panel dismisses it. The bell only appears when there is something to
 * show, and everything updates automatically as messages arrive or are read.
 *
 * Mount it once near the top of your Compose hierarchy so it overlays the rest of your content:
 * ```
 * Box(modifier = Modifier.fillMaxSize()) {
 *     AppContent()
 *     NotificationInboxOverlay()
 * }
 * ```
 * For custom placement (e.g. a bell in your toolbar, or the list embedded in a screen), use
 * [NotificationInboxBell] and [NotificationInboxView] directly instead.
 *
 * @param modifier Modifier applied to the root overlay container.
 */
@Composable
fun NotificationInboxOverlay(
    modifier: Modifier = Modifier
) {
    val controller = rememberInboxController()
    NotificationInboxOverlay(modifier = modifier, controller = controller)
}

/**
 * Internal overload that accepts the [VisualInboxController] directly so Compose UI tests can drive
 * the overlay with a fake [VisualInbox], and so the bell + panel share a single controller/state.
 */
@Composable
internal fun NotificationInboxOverlay(
    modifier: Modifier = Modifier,
    controller: VisualInboxController
) {
    var panelExpanded by remember { mutableStateOf(false) }

    // Reactive state: re-derived automatically on every relevant store change (see uiStateFlow),
    // so the bell/panel/badge update with no recomposition or user navigation required.
    // Build the Flow once per controller (not on every recomposition) so collection is stable.
    // collectAsStateWithLifecycle pauses collection while the host is STOPPED (backgrounded / overlay
    // off-screen) and resumes on start, avoiding wasted work when the inbox isn't visible.
    val state by remember(controller) { controller.uiStateFlow() }
        .collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))

    // Auto-close the panel + hide the bell when the inbox is no longer renderable. Dismissing the
    // last message empties the list -> the data layer reports the inbox no longer Visible -> the
    // panel collapses and the bell unmounts (see the guard below).
    val hasRenderableMessages = rememberHasRenderableMessages(state)
    LaunchedEffect(state.isVisible, hasRenderableMessages) {
        if (!state.isVisible || !hasRenderableMessages) {
            panelExpanded = false
        }
    }

    // The bell only appears when the inbox is renderable: enabled+Visible AND at least one message has
    // a template. When the panel is open we keep the overlay mounted regardless so the close animation
    // can play out.
    val canShowChrome = state.isVisible && hasRenderableMessages
    if (!canShowChrome && !panelExpanded) {
        return
    }

    val colors = rememberInboxColors((state.visibility as? InboxVisibility.Visible)?.branding)

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = panelExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            // Full-bleed scrim: dims + captures touches so nothing falls through to host content
            // behind the open panel; a tap dismisses the panel. No ripple/indication so it doesn't
            // flash on tap. Dim 0.32 black, matched to iOS.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { panelExpanded = false }
                    )
                    .semantics { contentDescription = "Close inbox" }
            )
        }

        // Slide-out hovering panel: a floating card (margins on all sides, rounded corners) wrapping
        // the inbox list. Aligned top-end so its top margin is honored.
        val panelShape = RoundedCornerShape(colors.cornerRadius)
        AnimatedVisibility(
            visible = panelExpanded,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            InboxListContent(
                controller = controller,
                colors = colors,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = PANEL_MARGIN,
                        end = PANEL_MARGIN,
                        top = PANEL_MARGIN,
                        bottom = PANEL_BOTTOM_MARGIN
                    )
                    .widthIn(max = 480.dp)
                    .shadow(8.dp, panelShape)
                    .clip(panelShape)
                    .background(colors.panelColor)
                    // Absorb taps inside the card so they don't fall through to the dismiss scrim
                    // (only the scrim closes the inbox). Tap-only absorber: unlike pointerInput,
                    // clickable does not eat scroll/drag, so the list still scrolls.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            )
        }

        // Floating bell, pinned bottom-end. Tapping toggles the panel.
        InboxBellContent(
            unopenedCount = state.unopenedCount,
            colors = colors,
            onClick = { panelExpanded = !panelExpanded },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }
}

/** The bell circle + unread badge. Pure UI — no data access; callers supply [unopenedCount]. */
@Composable
private fun InboxBellContent(
    unopenedCount: Int,
    colors: InboxColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.bellColor)
                .semantics { contentDescription = "Notifications inbox" }
                .clickable(role = Role.Button, onClick = onClick)
        ) {
            Image(
                painter = painterResource(id = R.drawable.cio_inbox_notifications),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.bellIconColor)
            )
        }

        if (unopenedCount > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .semantics { contentDescription = "$unopenedCount unread notifications" }
                    .heightIn(min = 16.dp)
                    .widthIn(min = 16.dp)
                    .clip(CircleShape)
                    .background(colors.badgeColor)
                    .padding(horizontal = 4.dp)
            ) {
                BasicText(
                    text = unopenedCount.toString(),
                    style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

/**
 * The inbox content (loading / empty / Jist-rendered list), shared by [NotificationInboxView] and
 * the [NotificationInboxOverlay] panel. Collects state from [controller], marks shown messages
 * opened, reports per-message "shown", and routes message actions (dismiss / nav). Fills [modifier]
 * and brings no card chrome of its own.
 */
@Composable
private fun InboxListContent(
    controller: VisualInboxController,
    colors: InboxColors,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by remember(controller) { controller.uiStateFlow() }
        .collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))

    // While the inbox content is shown, mark the currently-unread messages opened (deduped in the
    // controller). For the overlay this fires when the panel opens; for a standalone view, on appear.
    LaunchedEffect(state.visibility) {
        controller.markOpenMessagesOpened(state.visibility)
    }

    val visible = state.visibility as? InboxVisibility.Visible
    val templates = remember(state.templatesJson) {
        InboxJistDecoder.decodeTemplates(state.templatesJson)
    }
    val theme = remember(visible?.branding) {
        InboxJistDecoder.toJsonObject(visible?.branding?.theme)
    }

    Column(modifier = modifier) {
        when {
            state.loading -> LoadingState(color = colors.bellColor)
            // Render the list only when fully renderable (Visible + has messages); otherwise empty,
            // so the list is never fed null templates/theme.
            visible == null || state.messages.isEmpty() -> EmptyState(textColor = colors.textColorPrimary)
            else -> InboxMessageList(
                messages = state.messages,
                templates = templates,
                theme = theme,
                dividerColor = colors.dividerColor,
                onMessageShown = controller::notifyMessageShown,
                onMessageAction = { message, event ->
                    // Controller resolves the action (dismiss / track+intercept / default nav) and
                    // returns a nav instruction; we (owning the Context) run it.
                    when (val nav = controller.handleAction(state.visibility, message, event)) {
                        is InboxNavigation.OpenUrl -> openUrlInBrowser(context, nav.url)
                        InboxNavigation.None -> Unit
                    }
                }
            )
        }
    }
}

@Composable
private fun InboxMessageList(
    messages: List<JistInboxMessage>,
    templates: Map<String, List<io.customer.jist.JistTemplate>>,
    theme: JsonObject,
    dividerColor: Color,
    onMessageShown: (JistInboxMessage) -> Unit,
    onMessageAction: (JistInboxMessage, JistActionEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // No-template fallback (item 16): a message whose `type` has no decoded template can't be
    // rendered — skip it (do NOT render a blank row) and log so it's diagnosable.
    val renderable = remember(messages, templates) {
        messages.filter { message ->
            (message.type in templates).also { hasTemplate ->
                if (!hasTemplate) {
                    SDKComponent.logger.error(
                        "$INBOX_LOG_TAG skipping message ${message.queueId}: " +
                            "no template for type '${message.type}'"
                    )
                }
            }
        }
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(renderable, key = { it.queueId }) { message ->
            // Report "shown" once when the row enters composition (controller dedupes per session).
            LaunchedEffect(message.queueId) { onMessageShown(message) }
            // Decode the per-row Jist data once per message (not on every recomposition).
            val data = remember(message) { InboxJistDecoder.decodeData(message) }
            // Render with Jist: `name` selects the template by message type, `data` is the typed
            // properties, `templates`/`theme` come from the data layer, `mode = Auto` follows the
            // system light/dark setting, `formatDate` renders web-aligned relative time.
            JistView(
                name = message.type,
                templates = templates,
                data = data,
                theme = theme,
                mode = JistMode.Auto,
                formatDate = { iso, name -> InboxJistDecoder.formatRelativeDate(iso, name) },
                onAction = { event -> onMessageAction(message, event) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(dividerColor)
            )
        }
    }
}

/**
 * Default navigation for a resolved openUrl action (item 12): open [url] in the system browser via
 * an ACTION_VIEW intent. `FLAG_ACTIVITY_NEW_TASK` is set so it works from a non-Activity context.
 * Robust to a malformed url or a device with no browser: any failure is logged, never crashes.
 */
private fun openUrlInBrowser(context: android.content.Context, url: String) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (ex: Exception) {
        SDKComponent.logger.error("$INBOX_LOG_TAG failed to open url '$url' in browser: ${ex.message}")
    }
}

@Composable
private fun LoadingState(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CustomCircularProgressIndicator(
            color = color,
            modifier = Modifier.semantics {
                contentDescription = "Loading inbox"
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
        )
    }
}

@Composable
private fun EmptyState(
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = "No notifications yet",
            style = TextStyle(color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Normal),
            modifier = Modifier.semantics { contentDescription = "Inbox empty" }
        )
    }
}

@Composable
private fun CustomCircularProgressIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "ProgressTransition")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotationVal"
    )

    Canvas(modifier = modifier.size(40.dp)) {
        val strokeWidth = 4.dp.toPx()
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/** Builds a [VisualInboxController] wired to the SDK data layer + the host's inbox event listener. */
@Composable
private fun rememberInboxController(): VisualInboxController = remember {
    val module = ModuleMessagingInApp.instance()
    VisualInboxController(
        visualInbox = module.visualInbox(),
        // Host-registered inbox action/event listener (items 13/14), mirroring the in-app
        // eventListener; resolved from the same module config the host built. Null when none set.
        inboxEventListener = module.moduleConfig.inboxEventListener
    )
}

/**
 * Whether any selected message can actually render — its `type` has a decoded template. A message
 * whose type has no template is skipped by the list, so when none are renderable there is nothing to
 * show and the overlay hides all chrome rather than leaving a bell over a blank panel.
 */
@Composable
private fun rememberHasRenderableMessages(state: VisualInboxUiState): Boolean {
    val templates = remember(state.templatesJson) {
        InboxJistDecoder.decodeTemplates(state.templatesJson)
    }
    return remember(state.messages, templates) {
        state.messages.any { it.type in templates }
    }
}

/** Resolved chrome colors for the overlay. See [rememberInboxColors] for the resolution order. */
private data class InboxColors(
    val bellColor: Color,
    val bellIconColor: Color,
    val panelColor: Color,
    val textColorPrimary: Color,
    val dividerColor: Color,
    val badgeColor: Color,
    val cornerRadius: Dp
)

/**
 * Resolves the overlay's chrome colors, driven by backend branding so they are configurable per
 * workspace across all consumer apps. Every value is resolved in this priority order, with the
 * literals serving only as a last-resort floor:
 *   1. `patterns.modes.dark.inbox.*` — dark mode only, AND only when the workspace configured a
 *      dark palette (`patterns.modes.dark` is OPTIONAL; absent in many workspaces),
 *   2. `patterns.inbox.*` — the workspace's configured (light) inbox chrome,
 *   3. the host app's Android theme attr (`colorAccent` / `colorBackground` / `textColor*`),
 *   4. a literal default.
 */
@Composable
private fun rememberInboxColors(branding: Branding? = null): InboxColors {
    val isDarkTheme = isSystemInDarkTheme()
    // Tier 3 (host theme) fallbacks, used when the workspace has not configured the branding token.
    val accent = rememberThemeColor(android.R.attr.colorAccent, Color(0xFF3451FF))
    val surface = rememberThemeColor(
        android.R.attr.colorBackground,
        if (isDarkTheme) Color(0xFF121212) else Color.White
    )
    val textPrimary = rememberThemeColor(
        android.R.attr.textColorPrimary,
        if (isDarkTheme) Color.White else Color.Black
    )
    val textColorSecondary = rememberThemeColor(
        android.R.attr.textColorSecondary,
        if (isDarkTheme) Color.LightGray else Color.DarkGray
    )

    return remember(branding, isDarkTheme, accent, surface, textPrimary, textColorSecondary) {
        val light = branding?.inboxChrome
        // Dark overrides are an OPTIONAL raw map (shape mirrors patterns.inbox, nested under
        // modes.dark.inbox). Only consulted in dark mode; absent workspaces fall through to `light`.
        val dark: Map<*, *>? =
            if (isDarkTheme) branding?.patterns?.modes?.dark?.get("inbox") as? Map<*, *> else null

        val bellColor = dark.childStr("floatingIcon", "background").toColorOrNull()
            ?: light?.floatingIcon?.background.toColorOrNull()
            ?: accent
        val bellIconColor = dark.childStr("floatingIcon", "color").toColorOrNull()
            ?: light?.floatingIcon?.color.toColorOrNull()
            // Final fallback: contrast against the resolved bell so a light accent (e.g. Samsung One
            // UI resolves colorAccent to white) never yields a white icon on a white bell.
            ?: if (bellColor.luminance() > 0.5f) Color.Black else Color.White
        val panelColor = dark.str("background").toColorOrNull()
            ?: light?.background.toColorOrNull()
            ?: surface
        val dividerColor = (dark.str("dividerColor") ?: dark.str("borderColor")).toColorOrNull()
            ?: (light?.dividerColor ?: light?.borderColor).toColorOrNull()
            ?: textColorSecondary.copy(alpha = 0.12f)
        val badgeColor = dark.childStr("unreadIndicator", "background").toColorOrNull()
            ?: light?.unreadIndicator?.background.toColorOrNull()
            ?: Color(0xFFE53935)
        val cornerRadius = light?.cornerRadius?.dp ?: PANEL_CORNER_RADIUS

        InboxColors(
            bellColor = bellColor,
            bellIconColor = bellIconColor,
            panelColor = panelColor,
            textColorPrimary = textPrimary,
            dividerColor = dividerColor,
            badgeColor = badgeColor,
            cornerRadius = cornerRadius
        )
    }
}

/** Reads a top-level String value from a raw branding (dark-mode override) map, or null. */
private fun Map<*, *>?.str(key: String): String? = this?.get(key) as? String

/** Reads a String from a nested child object of a raw branding (dark-mode override) map, or null. */
private fun Map<*, *>?.childStr(child: String, key: String): String? =
    (this?.get(child) as? Map<*, *>)?.get(key) as? String

/**
 * Parses a branding hex color string (`#RRGGBB` or `#RRGGBBAA`, CSS byte order) into a Compose
 * [Color], or null when the value is absent / malformed so callers fall through to the next tier.
 */
private fun String?.toColorOrNull(): Color? {
    val hex = this?.trim()?.removePrefix("#") ?: return null
    return try {
        when (hex.length) {
            6 -> Color(0xFF000000L or hex.toLong(16))
            8 -> {
                val rgba = hex.toLong(16)
                val alpha = rgba and 0xFF
                val rgb = rgba ushr 8
                Color((alpha shl 24) or rgb)
            }
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

@Composable
private fun rememberThemeColor(attrResId: Int, fallbackColor: Color): Color {
    val context = LocalContext.current
    return remember(context, attrResId, fallbackColor) {
        try {
            val typedValue = TypedValue()
            if (context.theme.resolveAttribute(attrResId, typedValue, true)) {
                if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    Color(typedValue.data)
                } else if (typedValue.resourceId != 0) {
                    Color(ContextCompat.getColor(context, typedValue.resourceId))
                } else {
                    fallbackColor
                }
            } else {
                fallbackColor
            }
        } catch (_: Throwable) {
            fallbackColor
        }
    }
}

/** Consistent, greppable prefix for visual-inbox overlay log lines (matches the data layer's). */
private const val INBOX_LOG_TAG = "[CIO-Inbox]"

/** Hovering-panel margins: 16dp top/horizontal; a larger bottom inset clears the floating bell. */
private val PANEL_MARGIN = 16.dp
private val PANEL_BOTTOM_MARGIN = 88.dp
private val PANEL_CORNER_RADIUS = 12.dp

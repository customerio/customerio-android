package io.customer.messaginginbox

import android.util.TypedValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.core.net.toUri
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
 * bottom sheet, use [NotificationInboxOverlay] instead.
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

    val branding = (state.visibility as? InboxVisibility.Visible)?.branding
    val chrome = rememberInboxChrome(branding)
    InboxBellContent(
        unopenedCount = state.unopenedCount,
        showAlert = chrome.showAlert,
        bellSvg = chrome.bellSvg,
        colors = rememberInboxColors(branding),
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * The Visual Notification Inbox **message list** — the Jist-rendered messages — that you can embed
 * directly in your own screen (or present in a sheet/dialog). It fills the [modifier] you give it
 * and brings no surrounding chrome (no card/scrim), so you control the placement and container.
 *
 * Showing this view marks the currently-unread messages as opened (mirroring the sheet-open
 * behavior); tapping a message dismisses it or runs its action. For the ready-made floating bell +
 * bottom sheet, use [NotificationInboxOverlay]; to drive your own bell, use [NotificationInboxBell].
 *
 * @param modifier Modifier applied to the list container.
 * @param onDismissRequest invoked when a tapped message navigates away (opens a url / deep link, or
 *   the host listener handled a navigating action). If you present this view in your own sheet or
 *   dialog, dismiss it here so the inbox doesn't linger over the destination. Null (default) = the
 *   view stays put (e.g. when embedded inline in a screen).
 */
@Composable
fun NotificationInboxView(
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null
) {
    val controller = rememberInboxController()
    // Collect state for branding-driven chrome colors (the bell/overlay do the same); InboxListContent
    // collects its own state for the message list.
    val state by remember(controller) { controller.uiStateFlow() }
        .collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))
    InboxListContent(
        controller = controller,
        colors = rememberInboxColors((state.visibility as? InboxVisibility.Visible)?.branding),
        onNavigatedAway = onDismissRequest,
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Drop-in Compose overlay that shows the Customer.io Visual Notification Inbox on top of your app:
 * a floating [NotificationInboxBell] pinned to the branding-configured corner (default bottom-end)
 * that slides a [NotificationInboxView] up in a bottom sheet; tapping the scrim outside the sheet
 * dismisses it. The bell only appears when there is something to show, and everything updates
 * automatically as messages arrive or are read.
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
 * the overlay with a fake [VisualInbox], and so the bell + sheet share a single controller/state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationInboxOverlay(
    modifier: Modifier = Modifier,
    controller: VisualInboxController
) {
    var sheetExpanded by remember { mutableStateOf(false) }

    // Reactive state: re-derived automatically on every relevant store change (see uiStateFlow),
    // so the bell/sheet/badge update with no recomposition or user navigation required.
    // Build the Flow once per controller (not on every recomposition) so collection is stable.
    // collectAsStateWithLifecycle pauses collection while the host is STOPPED (backgrounded / overlay
    // off-screen) and resumes on start, avoiding wasted work when the inbox isn't visible.
    //
    // Reactive-lifecycle: this collection is anchored to the always-mounted overlay composable, NOT
    // to the conditionally-rendered bell. The empty-inbox early-return below only skips rendering; it
    // does not remove this composable (or its subscription) from composition, so the bell reappears
    // when a message later arrives to an empty inbox. (Keep the collection decoupled from the bell —
    // the iOS overlay had a bug where a bell-scoped teardown cancelled the subscription permanently.)
    val state by remember(controller) { controller.uiStateFlow() }
        .collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))

    // Auto-close the sheet + hide the bell when the inbox is no longer renderable. Dismissing the
    // last message empties the list -> the data layer reports the inbox no longer Visible -> the
    // sheet collapses and the bell unmounts (see the guard below).
    val hasRenderableMessages = rememberHasRenderableMessages(state)
    LaunchedEffect(state.isVisible, hasRenderableMessages) {
        if (!state.isVisible || !hasRenderableMessages) {
            sheetExpanded = false
        }
    }

    // The bell only appears when the inbox is renderable: enabled+Visible AND at least one message has
    // a template. When the sheet is open we keep the overlay mounted regardless.
    val canShowChrome = state.isVisible && hasRenderableMessages
    if (!canShowChrome && !sheetExpanded) {
        return
    }

    val branding = (state.visibility as? InboxVisibility.Visible)?.branding
    val colors = rememberInboxColors(branding)
    val chrome = rememberInboxChrome(branding)

    // Floating bell, pinned to the branding-configured corner (default bottom-end). Tapping opens the
    // sheet. The anchor Box fills the host so the position alignment resolves against the full area.
    // The bell is NOT toggled off while the sheet is open: the sheet presents in its own window above
    // this content with its own scrim, so the bell simply sits behind it — toggling it on sheet
    // open/close would make it flicker in and out.
    Box(modifier = modifier.fillMaxSize()) {
        if (canShowChrome) {
            InboxBellContent(
                unopenedCount = state.unopenedCount,
                showAlert = chrome.showAlert,
                bellSvg = chrome.bellSvg,
                colors = colors,
                onClick = { sheetExpanded = true },
                modifier = Modifier
                    .align(chrome.position.alignment)
                    .padding(16.dp)
            )
        }
    }

    // Native Material3 bottom sheet: slides up from the bottom with a drag handle (grabber), a scrim
    // + drag-to-dismiss, and partial/expanded detents (opens partially; drag up to expand) — the
    // parallel to the iOS overlay's .sheet with medium/large detents. It presents in its own window,
    // so it is composed outside the anchor Box. Gate on canShowChrome too: if the inbox empties while
    // the sheet is open (dismissing the last message -> Hidden), the sheet closes with the inbox.
    if (sheetExpanded && canShowChrome) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { sheetExpanded = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = colors.cornerRadius, topEnd = colors.cornerRadius),
            containerColor = colors.panelColor
            // dragHandle omitted: ModalBottomSheet already uses BottomSheetDefaults.DragHandle by
            // default. Passing it explicitly generated a public ComposableSingletons lambda in the
            // API dump for no behavior change.
        ) {
            InboxListContent(
                controller = controller,
                colors = colors,
                // Close the sheet when a tapped message navigates via a deep link, so the deep-linked
                // screen isn't left behind the sheet (openUrl external does not close).
                onNavigatedAway = { sheetExpanded = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * The bell circle + unread badge. Pure UI — no data access; callers supply [unopenedCount],
 * [showAlert] (branding `unreadIndicator.showAlert` — whether to show the badge), and [bellSvg]
 * (branding `floatingIcon.svg` — rendered dynamically, falling back to the bundled glyph).
 */
@Composable
private fun InboxBellContent(
    unopenedCount: Int,
    showAlert: Boolean,
    bellSvg: String?,
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
            // Prefer the workspace's configured bell SVG; fall back to the bundled glyph when it is
            // absent or unparseable. The art is built (and validated) once per svg here — malformed
            // path data yields null (not a crash), and the parse is cached rather than run per frame.
            val bellArt = remember(bellSvg) { bellSvg?.let(InboxBellSvg::buildArt) }
            if (bellArt != null) {
                InboxBellIcon(
                    art = bellArt,
                    tint = colors.bellIconColor,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.cio_inbox_notifications),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.bellIconColor)
                )
            }
        }

        // Badge shows only when there are unread messages AND branding has not disabled the alert
        // (default show when unset), matching web renderBadge (hidden when count===0 || !showAlert).
        if (unopenedCount > 0 && showAlert) {
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
    modifier: Modifier = Modifier,
    // Invoked when a tapped message navigates away via a deep link, so a host presenting this in a
    // sheet/dialog can dismiss it. Null (the default) for the embeddable NotificationInboxView.
    onNavigatedAway: (() -> Unit)? = null
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
            // Inbox is not renderable (disabled workspace, missing templates/branding). Render
            // nothing — matching the overlay, which hides its chrome entirely in this state, and the
            // iOS NotificationInboxView — rather than a stale list or a misleading "empty" placeholder.
            visible == null -> Unit
            // Visible but genuinely caught up (no messages). The list is only rendered in `else`
            // (Visible + has messages), so it is never fed null templates/theme.
            state.messages.isEmpty() -> EmptyState(textColor = colors.textColorPrimary)
            else -> InboxMessageList(
                messages = state.messages,
                templates = templates,
                theme = theme,
                dividerColor = colors.dividerColor,
                onMessageShown = { message -> controller.notifyMessageShown(state.visibility, message) },
                onMessageAction = { message, event ->
                    // Controller resolves the action (dismiss / track+intercept / default nav) and
                    // returns a nav instruction; we (owning the Context) run it.
                    when (val nav = controller.handleAction(state.visibility, message, event)) {
                        is InboxNavigation.OpenUrl -> {
                            // Open externally; on success dismiss so the inbox doesn't linger behind the
                            // browser / destination. A failed open (no browser / bad url) keeps the sheet
                            // up rather than closing onto nothing (mirrors OpenDeeplink).
                            if (openUrlInBrowser(context, nav.url)) onNavigatedAway?.invoke()
                        }
                        is InboxNavigation.OpenDeeplink -> {
                            // Route through the app's deep-link handling; on success, dismiss so the
                            // deep-linked screen isn't left behind the sheet. A failed open keeps it open.
                            if (openDeepLink(context, nav.url)) onNavigatedAway?.invoke()
                        }
                        // Host handled a navigating action itself — dismiss without the SDK navigating.
                        InboxNavigation.Dismiss -> onNavigatedAway?.invoke()
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
 *
 * Returns true when the browser was launched, false on failure — so the caller only dismisses the
 * inbox when navigation actually happened (mirroring [openDeepLink]); a failed open keeps the sheet
 * up rather than closing onto nothing.
 */
private fun openUrlInBrowser(context: android.content.Context, url: String): Boolean {
    return try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (ex: Exception) {
        SDKComponent.logger.error("$INBOX_LOG_TAG failed to open url '$url' in browser: ${ex.message}")
        false
    }
}

/**
 * Route a resolved `openDeeplink` action (item 12) through the app's deep-link handling, mirroring
 * how push notifications and in-app messages open deep links (see messagingpush `DeepLinkUtil`):
 * prefer an activity in the **host app** (the app's own declared intent-filters handle the link
 * in-app), and only fall back to an **external** handler (e.g. a browser for an https deeplink) when
 * the host app declares none. Returns true when an activity was launched (so the caller can dismiss
 * the inbox), false when nothing could handle the link. Never throws.
 *
 * (Implemented here rather than via messagingpush's `DeepLinkUtil` because `messaginginbox` depends
 * only on `messaginginapp`; this keeps the routing behavior without adding a module dependency.)
 */
private fun openDeepLink(context: android.content.Context, url: String): Boolean {
    val uri = url.toUri()
    val packageManager = context.packageManager

    // 1. Host app first: an ACTION_VIEW intent scoped to this package resolves only the app's own
    //    activities/intent-filters, so the link opens in-app rather than bouncing to a browser.
    val hostIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .setPackage(context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (hostIntent.resolveActivity(packageManager) != null) {
        return startDeepLinkActivity(context, hostIntent, url)
    }

    // 2. Fall back to any app that can open the link (e.g. a browser for an https deeplink).
    val externalIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (externalIntent.resolveActivity(packageManager) != null) {
        return startDeepLinkActivity(context, externalIntent, url)
    }

    SDKComponent.logger.info("$INBOX_LOG_TAG no activity found to handle deeplink '$url'")
    return false
}

private fun startDeepLinkActivity(context: android.content.Context, intent: android.content.Intent, url: String): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (ex: Exception) {
        SDKComponent.logger.error("$INBOX_LOG_TAG failed to open deeplink '$url': ${ex.message}")
        false
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
        // eventListener. Read from the module on each callback so a listener registered at runtime
        // via ModuleMessagingInApp.setInboxEventListener takes effect; null when none is set.
        inboxEventListenerProvider = { module.inboxEventListener }
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

/**
 * Non-color branding chrome for the bell + sheet: where the bell is pinned ([position]), whether the
 * unread badge is shown ([showAlert]), and the raw bell glyph SVG ([bellSvg]). Kept separate from
 * [InboxColors] so the color resolution stays focused.
 */
private data class InboxChromeConfig(
    val position: InboxBellPosition,
    val showAlert: Boolean,
    val bellSvg: String?
)

/** Resolves [InboxChromeConfig] from branding: `patterns.inbox.position` / `unreadIndicator.showAlert` / `floatingIcon.svg`. */
@Composable
private fun rememberInboxChrome(branding: Branding?): InboxChromeConfig = remember(branding) {
    val chrome = branding?.inboxChrome
    InboxChromeConfig(
        position = InboxBellPosition.resolve(chrome?.position),
        // Default to showing the badge when the workspace hasn't configured showAlert.
        showAlert = chrome?.unreadIndicator?.showAlert ?: true,
        bellSvg = branding?.floatingIcon?.svg
    )
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

/** Fallback corner radius for the sheet's rounded top when branding does not configure one. */
private val PANEL_CORNER_RADIUS = 12.dp

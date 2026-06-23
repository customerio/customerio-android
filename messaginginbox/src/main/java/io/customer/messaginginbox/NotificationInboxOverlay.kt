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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.jist.JistActionEvent
import io.customer.jist.JistMode
import io.customer.jist.JistView
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.jist.JistInboxMessage
import io.customer.sdk.core.di.SDKComponent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Drop-in Compose overlay that shows the Customer.io Visual Notification Inbox on top of your app.
 *
 * It renders a floating notification bell with an unread badge. Tapping the bell slides out a
 * panel listing the user's inbox messages; tapping outside the panel dismisses it. The bell only
 * appears when there is something to show, and it updates automatically as messages arrive or are
 * read — you do not need to refresh or re-navigate.
 *
 * Mount it once near the top of your Compose hierarchy so it overlays the rest of your content:
 * ```
 * Box(modifier = Modifier.fillMaxSize()) {
 *     AppContent()
 *     NotificationInboxOverlay()
 * }
 * ```
 *
 * @param modifier Modifier applied to the root overlay container.
 */
@InternalCustomerIOApi
@Composable
fun NotificationInboxOverlay(
    modifier: Modifier = Modifier
) {
    val controller = remember {
        VisualInboxController(ModuleMessagingInApp.instance().visualInbox())
    }
    NotificationInboxOverlay(modifier = modifier, controller = controller)
}

/**
 * Internal overload that accepts the [VisualInboxController] directly so Compose UI tests can
 * drive the overlay with a fake [VisualInbox]. The public entry point delegates here after
 * resolving the real data layer from the SDK component.
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
    val uiStateFlow = remember(controller) { controller.uiStateFlow() }
    val state by uiStateFlow.collectAsStateWithLifecycle(initialValue = VisualInboxUiState(loading = true))

    // Whether any selected message can actually render (its `type` has a decoded template). A message
    // whose type has no template is skipped by the list, so when NONE are renderable there is nothing
    // to show — chrome is hidden (below) rather than left as a bell over a blank panel.
    val renderTemplates = remember(state.templatesJson) {
        InboxJistDecoder.decodeTemplates(state.templatesJson)
    }
    val hasRenderableMessages = remember(state.messages, renderTemplates) {
        state.messages.any { it.type in renderTemplates }
    }

    // When the panel opens, auto-mark the currently-selected unopened messages as opened (exactly
    // once, via the controller's dedupe + in-flight guard). The mark dispatches a store change,
    // which re-emits through uiStateFlow above so the messages and unread badge update reactively.
    LaunchedEffect(panelExpanded, state.visibility) {
        if (!panelExpanded) return@LaunchedEffect
        controller.markOpenMessagesOpened(state.visibility)
    }

    // Auto-close the panel + hide the bell when the inbox is no longer renderable. This matches the
    // web/iOS model: dismissing the last message empties the list -> the data layer reports the
    // inbox no longer Visible (or messages becomes empty) -> the panel collapses and the bell
    // unmounts (see the `isVisible && panelExpanded` guard below). Without this, the panel would
    // stay open over an empty list after the final dismiss.
    LaunchedEffect(state.isVisible, hasRenderableMessages) {
        if (!state.isVisible || !hasRenderableMessages) {
            panelExpanded = false
        }
    }

    // The bell only appears when the inbox is renderable: enabled+Visible per the data layer AND at
    // least one message has a template to render. When the panel is open we keep the overlay mounted
    // regardless so the close animation can play out.
    val canShowChrome = state.isVisible && hasRenderableMessages
    if (!canShowChrome && !panelExpanded) {
        return
    }

    val isDarkTheme = isSystemInDarkTheme()
    val bellColor = rememberThemeColor(android.R.attr.colorAccent, Color(0xFF3451FF))
    val panelColor = rememberThemeColor(android.R.attr.colorBackground, if (isDarkTheme) Color(0xFF121212) else Color.White)
    val textColorPrimary = rememberThemeColor(android.R.attr.textColorPrimary, if (isDarkTheme) Color.White else Color.Black)
    val textColorSecondary = rememberThemeColor(android.R.attr.textColorSecondary, if (isDarkTheme) Color.LightGray else Color.DarkGray)
    val dividerColor = textColorSecondary.copy(alpha = 0.12f)
    val badgeColor = Color(0xFFE53935)

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = panelExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Scrim dim matched to iOS: 0.32 black (was ~0.6) so both platforms dim equally.
                    .background(Color.Black.copy(alpha = 0.32f))
                    // Full-bleed scrim captures touches so nothing falls through to host content
                    // behind the open panel; a tap dismisses the panel. No ripple/indication so
                    // the scrim does not flash on tap.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { panelExpanded = false }
                    )
                    .semantics { contentDescription = "Close inbox" }
            )
        }

        // Slide-out hovering panel. Aligned to the top so its top margin is honored; the panel
        // itself applies the all-sides margins (it is a floating card, not a full-height sheet).
        AnimatedVisibility(
            visible = panelExpanded,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            InboxPanel(
                state = state,
                bellColor = bellColor,
                panelColor = panelColor,
                textColorPrimary = textColorPrimary,
                dividerColor = dividerColor,
                onMessageAction = { message, event ->
                    handleInboxAction(controller, state.visibility, message, event)
                }
            )
        }

        // Floating bell button with unread badge.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(bellColor)
                    .semantics { contentDescription = "Notifications inbox" }
                    .clickable(
                        role = Role.Button,
                        onClick = { panelExpanded = !panelExpanded }
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.cio_inbox_notifications),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White)
                )
            }

            if (state.unopenedCount > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .semantics {
                            contentDescription = "${state.unopenedCount} unread notifications"
                        }
                        .heightIn(min = 16.dp)
                        .widthIn(min = 16.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .padding(horizontal = 4.dp)
                ) {
                    BasicText(
                        text = state.unopenedCount.toString(),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxPanel(
    state: VisualInboxUiState,
    bellColor: Color,
    panelColor: Color,
    textColorPrimary: Color,
    dividerColor: Color,
    onMessageAction: (JistInboxMessage, JistActionEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Decode the raw render inputs once per visibility change. Templates + theme are stable for
    // the duration the panel shows a given snapshot.
    val visible = state.visibility as? io.customer.messaginginapp.inbox.data.InboxVisibility.Visible
    val templates = remember(state.templatesJson) {
        InboxJistDecoder.decodeTemplates(state.templatesJson)
    }
    val theme = remember(visible?.branding) {
        InboxJistDecoder.toJsonObject(visible?.branding?.theme)
    }

    val panelShape = RoundedCornerShape(PANEL_CORNER_RADIUS)
    Box(
        // Hovering card (matches iOS): margins on ALL sides — 16dp top/horizontal and a larger
        // bottom inset that clears the floating bell — capped at a max width on large screens, with
        // rounded corners. Margins are applied outside the shaped panel so the slide-out animation still
        // translates the full card (corners included) off-screen.
        modifier = modifier
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
            .background(panelColor)
            // Absorb taps inside the panel so they don't fall through to the dismiss scrim behind it
            // (a tap on the panel must NOT close the inbox — only a tap on the scrim does). This is a
            // tap-only absorber: unlike an all-consuming pointerInput, clickable does NOT eat
            // scroll/drag gestures, so the LazyColumn below still scrolls. No ripple/indication so
            // the panel does not flash on tap.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        // No header (title / close button) — matches web. The panel closes via the scrim tap or by
        // tapping the bell again.
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState(bellColor = bellColor)
                // Render the list only when the inbox is fully renderable (Visible) and has
                // messages. Anything else (Hidden, or visible-but-no-messages) shows empty so the
                // list is never fed null templates/theme.
                visible == null || state.messages.isEmpty() -> EmptyState(textColorPrimary = textColorPrimary)
                else -> InboxMessageList(
                    messages = state.messages,
                    templates = templates,
                    theme = theme,
                    dividerColor = dividerColor,
                    onMessageAction = onMessageAction
                )
            }
        }
    }
}

@Composable
private fun InboxMessageList(
    messages: List<JistInboxMessage>,
    templates: Map<String, List<io.customer.jist.JistTemplate>>,
    theme: JsonObject,
    dividerColor: Color,
    onMessageAction: (JistInboxMessage, JistActionEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // No-template fallback (item 16): a message whose `type` has no decoded template can't be
    // rendered — skip it (do NOT render a blank row) and log so it's diagnosable. Filtering here
    // (rather than in the data layer) keeps the renderer the single owner of "is this renderable".
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
            // Decode the per-row Jist data once per message (not on every recomposition).
            val data = remember(message) { InboxJistDecoder.decodeData(message) }
            // Render each message with Jist: `name` selects the template by message type, `data`
            // is the message's typed properties, `templates`/`theme` come from the data layer.
            // `mode = Auto` lets Jist pick light/dark content per the system setting. `formatDate`
            // turns the decoder's raw ISO dates into web-aligned relative time.
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
 * Maps a Jist action to an inbox behavior. Web parity: a "dismiss" action removes (deletes) the
 * message. The live inbox templates emit the action as `name = "messageAction"` with the message's
 * `properties.messageAction = { behavior: "dismiss" }`, so the dismiss signal we match is
 * **`data.behavior == "dismiss"`**. We also accept the Jist-demo sentinels (`name == "dismiss"` or
 * `data.url == "#dismiss"`) as a fallback. Any other action (a real url / other behavior) is a
 * no-op for now — real-url/deep-link navigation is deferred (scope item 12); we log it so it's
 * visible during bring-up.
 */
private fun handleInboxAction(
    controller: VisualInboxController,
    visibility: io.customer.messaginginapp.inbox.data.InboxVisibility,
    message: JistInboxMessage,
    event: JistActionEvent
) {
    val isDismiss = actionBehavior(event) == DISMISS_BEHAVIOR ||
        event.name == DISMISS_ACTION_NAME ||
        actionUrl(event) == DISMISS_URL
    if (isDismiss) {
        controller.dismissMessage(visibility, message.queueId)
        return
    }
    // TODO (item 12 — deferred): real-url / deep-link navigation. TODO (item 13 — deferred): host
    // action callback API. For now, non-dismiss actions are a no-op.
    SDKComponent.logger.debug(
        "$INBOX_LOG_TAG action '${event.name}' (behavior=${actionBehavior(event)}, url=${actionUrl(event)}) " +
            "on ${message.queueId}: navigation deferred, no-op"
    )
}

/**
 * Extracts the `url` string from a Jist action event's data object, or null if absent / not a
 * string. Safe casts throughout: a non-object `data` or non-primitive `url` yields null rather
 * than throwing.
 */
private fun actionUrl(event: JistActionEvent): String? =
    ((event.data as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull

/**
 * Extracts the `behavior` string from a Jist action event's data object (e.g. the live inbox's
 * `messageAction = { behavior: "dismiss" }`), or null if absent / not a string. Safe casts.
 */
private fun actionBehavior(event: JistActionEvent): String? =
    ((event.data as? JsonObject)?.get("behavior") as? JsonPrimitive)?.contentOrNull

@Composable
private fun LoadingState(
    bellColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CustomCircularProgressIndicator(
            color = bellColor,
            modifier = Modifier.semantics {
                contentDescription = "Loading inbox"
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
        )
    }
}

@Composable
private fun EmptyState(
    textColorPrimary: Color,
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
            style = TextStyle(
                color = textColorPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            ),
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

    Canvas(
        modifier = modifier
            .size(40.dp)
    ) {
        val strokeWidth = 4.dp.toPx()
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round
            )
        )
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

/** Jist action signals that mean "dismiss this message". `behavior` is the live inbox contract
 *  (`messageAction.behavior == "dismiss"`); `name`/`url` are Jist-demo fallbacks. */
private const val DISMISS_BEHAVIOR = "dismiss"
private const val DISMISS_ACTION_NAME = "dismiss"
private const val DISMISS_URL = "#dismiss"

/** Hovering-panel margins: 16dp top/horizontal; a larger bottom inset clears the floating bell. */
private val PANEL_MARGIN = 16.dp
private val PANEL_BOTTOM_MARGIN = 88.dp
private val PANEL_CORNER_RADIUS = 12.dp

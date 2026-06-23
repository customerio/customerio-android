package io.customer.messaginginbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.jist.JistView
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.jist.JistInboxMessage

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
    val uiStateFlow = remember(controller) { controller.uiStateFlow() }
    val state by uiStateFlow.collectAsState(initial = VisualInboxUiState(loading = true))

    // When the panel opens, auto-mark the currently-selected unopened messages as opened (exactly
    // once, via the controller's dedupe + in-flight guard). The mark dispatches a store change,
    // which re-emits through uiStateFlow above so the messages and unread badge update reactively.
    LaunchedEffect(panelExpanded, state.visibility) {
        if (!panelExpanded) return@LaunchedEffect
        controller.markOpenMessagesOpened(state.visibility)
    }

    // The bell only appears when the inbox is renderable per the data layer. When the panel is
    // open we keep the overlay mounted regardless so the close animation can play out.
    if (!state.isVisible && !panelExpanded) {
        return
    }

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
                    .background(Color(0x99000000))
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

        // Slide-out panel.
        AnimatedVisibility(
            visible = panelExpanded,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            InboxPanel(
                state = state,
                onClose = { panelExpanded = false }
            )
        }

        // Floating bell button with unread badge.
        BadgedBox(
            badge = {
                if (state.unopenedCount > 0) {
                    Badge(
                        modifier = Modifier.semantics {
                            contentDescription = "${state.unopenedCount} unread notifications"
                        }
                    ) {
                        Text(text = state.unopenedCount.toString())
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            FloatingActionButton(onClick = { panelExpanded = !panelExpanded }) {
                Icon(
                    painter = painterResource(id = R.drawable.cio_inbox_notifications),
                    contentDescription = "Notifications inbox"
                )
            }
        }
    }
}

@Composable
private fun InboxPanel(
    state: VisualInboxUiState,
    onClose: () -> Unit,
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

    Surface(
        // Fill the available width minus a horizontal margin on each side, capped at a max width
        // on large screens. Padding is applied outside the Surface so the slide-out animation
        // still translates the full panel off-screen.
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .padding(horizontal = 16.dp)
            // Absorb taps inside the panel so they don't fall through to the dismiss scrim behind it
            // (a tap on the panel must NOT close the inbox — only a tap on the scrim does). This is a
            // tap-only absorber: unlike an all-consuming pointerInput, clickable does NOT eat
            // scroll/drag gestures, so the LazyColumn below still scrolls. No ripple/indication so
            // the panel does not flash on tap.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        elevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Inbox", fontWeight = FontWeight.Bold)
                Text(
                    text = "Close",
                    modifier = Modifier.clickable(onClick = onClose)
                )
            }
            Divider()

            when {
                state.loading -> LoadingState()
                // Render the list only when the inbox is fully renderable (Visible) and has
                // messages. Anything else (Hidden, or visible-but-no-messages) shows empty so the
                // list is never fed null templates/theme.
                visible == null || state.messages.isEmpty() -> EmptyState()
                else -> InboxMessageList(
                    messages = state.messages,
                    templates = templates,
                    theme = theme
                )
            }
        }
    }
}

@Composable
private fun InboxMessageList(
    messages: List<JistInboxMessage>,
    templates: Map<String, List<io.customer.jist.JistTemplate>>,
    theme: kotlinx.serialization.json.JsonObject,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(messages, key = { it.queueId }) { message ->
            // Decode the per-row Jist data once per message (not on every recomposition).
            val data = remember(message) { InboxJistDecoder.decodeData(message) }
            // Render each message with Jist: `name` selects the template by message type, `data`
            // is the message's typed properties, `templates`/`theme` come from the data layer.
            JistView(
                name = message.type,
                templates = templates,
                data = data,
                theme = theme,
                onAction = {
                    // TODO (items 12/13 — deferred): map Jist actions to inbox click/track and
                    // deep-link handling. No-op for now.
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            Divider()
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading inbox" }
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No notifications yet",
            modifier = Modifier.semantics { contentDescription = "Inbox empty" }
        )
    }
}

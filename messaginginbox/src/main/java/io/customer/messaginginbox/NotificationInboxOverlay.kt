package io.customer.messaginginbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Divider
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.NotificationInboxChangeListener

/**
 * Opt-in Compose overlay that renders a visual notification inbox on top of the existing
 * headless inbox API (`ModuleMessagingInApp.instance().inbox()`).
 *
 * The overlay renders a floating action button with an unread badge. Tapping the button
 * toggles a slide-out panel that lists the current inbox messages as placeholder rows.
 *
 * Inbox state is read from the headless API: an initial fetch via `getMessages()` plus
 * live updates through a [NotificationInboxChangeListener] that is removed on dispose.
 *
 * When there are no messages the entire chrome (button and panel) is hidden.
 *
 * This is a Milestone 1 placeholder UI: rows are plain text derived from [InboxMessage]
 * fields. There is no Jist rendering or real templating yet.
 *
 * @param modifier Modifier applied to the root overlay container.
 * @param topic Optional topic filter forwarded to the headless inbox API.
 */
@Composable
fun NotificationInboxOverlay(
    modifier: Modifier = Modifier,
    topic: String? = null
) {
    var messages by remember { mutableStateOf<List<InboxMessage>>(emptyList()) }
    var panelExpanded by remember { mutableStateOf(false) }

    val inbox = remember { ModuleMessagingInApp.instance().inbox() }

    // Initial fetch of the current inbox state.
    LaunchedEffect(topic) {
        messages = inbox.getMessages(topic)
    }

    // Subscribe to live updates and clean up on dispose to avoid leaking the listener.
    DisposableEffect(inbox, topic) {
        val listener = object : NotificationInboxChangeListener {
            override fun onMessagesChanged(updated: List<InboxMessage>) {
                messages = updated
            }
        }
        inbox.addChangeListener(listener, topic)
        onDispose {
            inbox.removeChangeListener(listener)
        }
    }

    // No messages means no visible chrome at all.
    if (messages.isEmpty()) {
        return
    }

    val unreadCount = messages.count { !it.opened }

    Box(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        // Slide-out panel listing the messages as placeholder rows.
        AnimatedVisibility(
            visible = panelExpanded,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            InboxPanel(
                messages = messages,
                onClose = { panelExpanded = false }
            )
        }

        // Floating action button with unread badge.
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge { Text(text = unreadCount.toString()) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            FloatingActionButton(onClick = { panelExpanded = !panelExpanded }) {
                Text(text = "Inbox")
            }
        }
    }
}

@Composable
private fun InboxPanel(
    messages: List<InboxMessage>,
    onClose: () -> Unit
) {
    Surface(
        // Fill the available width minus a horizontal margin on each side, capped at a max
        // width on large screens (tablets). On phones this is screen-width-minus-margins;
        // on tablets it stops at 480dp. The padding is applied outside the Surface so the
        // slide-out animation still translates the full panel (Surface + margins) off-screen.
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .padding(horizontal = 16.dp),
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
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(messages) { message ->
                    InboxMessageRow(message = message)
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun InboxMessageRow(message: InboxMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Unread/read indicator dot.
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (message.opened) Color.Transparent else Color(0xFF2962FF),
                    shape = CircleShape
                )
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = message.inboxTitle(),
                fontWeight = if (message.opened) FontWeight.Normal else FontWeight.Bold
            )
            Text(text = if (message.opened) "Read" else "Unread")
        }
    }
}

/**
 * Derives a human-readable title for the placeholder row from the message properties,
 * falling back to identifiers when no title-like property is present.
 */
private fun InboxMessage.inboxTitle(): String {
    val titleKeys = listOf("title", "subject", "headline", "name")
    val titleValue = titleKeys
        .firstNotNullOfOrNull { key -> properties[key]?.toString()?.takeIf { it.isNotBlank() } }
    return titleValue ?: (deliveryId ?: queueId)
}

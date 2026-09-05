package io.customer.messaginginapp.type

/**
 * Host-provided accessibility (TalkBack) labels for the Visual Notification Inbox UI —
 * `NotificationInboxView`, `NotificationInboxBell` and `NotificationInboxOverlay`.
 *
 * The SDK ships **no** text of its own in the visual inbox: the empty state is an icon and the loading
 * state is a spinner, so nothing on screen needs translating. Accessibility labels are the one place a
 * string is still needed, and because the SDK cannot know the host app's language, every label is
 * optional and **null by default**. A null label emits no content description for that element: the
 * empty-state icon is decorative, the spinner is announced only as indeterminate progress, and the
 * bell stays focusable and tappable but unnamed (hiding it would leave TalkBack users no way into the
 * inbox). Provide values in whatever language your app ships in — typically from your own
 * `strings.xml`.
 *
 * ```kotlin
 * MessagingInAppModuleConfig.Builder(siteId, Region.US)
 *     .setNotificationInboxAccessibilityLabels(
 *         NotificationInboxAccessibilityLabels(
 *             bell = context.getString(R.string.inbox_bell),
 *             bellWithUnreadCount = { count ->
 *                 context.resources.getQuantityString(R.plurals.inbox_unread, count, count)
 *             },
 *             loadingIndicator = context.getString(R.string.inbox_loading),
 *             emptyState = context.getString(R.string.inbox_empty)
 *         )
 *     )
 *     .build()
 * ```
 *
 * Threading: [bellWithUnreadCount] is invoked on the main thread while the bell composes, each time
 * the unread count changes. Keep it cheap and side-effect free.
 *
 * @property bell label for the inbox bell button. Also used when the bell shows an unread badge but
 *   [bellWithUnreadCount] is not provided. null → the bell is announced as an unnamed button.
 * @property bellWithUnreadCount builds the bell's label while it shows an unread badge, given the
 *   unread count. A function (not a template) so hosts can apply their language's plural rules. null →
 *   falls back to [bell]. The badge itself is always hidden from TalkBack, so the count is announced
 *   only through this label, never as bare digits appended to the button.
 * @property loadingIndicator label announced for the loading spinner. null → none, leaving only the
 *   indeterminate-progress role that TalkBack describes in the device's own language.
 * @property emptyState label announced for the empty-state icon. null → the icon is decorative.
 */
class NotificationInboxAccessibilityLabels @JvmOverloads constructor(
    val bell: String? = null,
    val bellWithUnreadCount: ((Int) -> String)? = null,
    val loadingIndicator: String? = null,
    val emptyState: String? = null
)

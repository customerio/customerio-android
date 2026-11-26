package io.customer.messaginginapp.type

/**
 * How modal in-app messages are put on screen. Set it with
 * [io.customer.messaginginapp.MessagingInAppModuleConfig.Builder.setModalPresentation].
 */
enum class ModalPresentation {
    /**
     * Shows each modal in its own transparent activity. This is the default.
     *
     * Android cannot add that activity to the task of a host activity that is `singleInstance`, so the
     * modal starts in a second task. Reopening the app from Recents after Home then shows only the
     * modal. The same happens when the host activity uses a different or empty task affinity, or a
     * document launch mode. Hosts with any of these should use [HOST_WINDOW].
     */
    ACTIVITY,

    /**
     * Shows each modal in a full-screen transparent dialog attached to the host's resumed activity, so
     * the modal never needs a task of its own. As with [ACTIVITY], the modal stays with the activity it
     * opened over: another activity opened on top covers it until the user returns. When that activity
     * is recreated (rotation, dark mode) or finishes, the modal moves to the next resumed activity
     * without being counted again. When no host activity is resumed, the modal waits until one is.
     */
    HOST_WINDOW
}

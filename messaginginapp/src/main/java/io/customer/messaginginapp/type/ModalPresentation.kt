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
     * the modal never needs a task of its own. A modal that is showing carries over to the next
     * resumed host activity. When no host activity is resumed, the modal waits until one is.
     */
    HOST_WINDOW
}

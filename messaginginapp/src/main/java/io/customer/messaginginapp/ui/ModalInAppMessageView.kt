package io.customer.messaginginapp.ui

import android.content.Context
import android.util.AttributeSet

/**
 * Final implementation of the InAppMessageHostView for displaying modal in-app messages.
 * The view should be directly added to activity layout for displaying modal in-app messages.
 */
internal class ModalInAppMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : InAppMessageBaseView(context, attrs, defStyleAttr)

package io.customer.messaginginapp.gist.presentation

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.customer.messaginginapp.R
import io.customer.messaginginapp.gist.data.model.MessagePosition

class PositionBottomSheet(
    private val context: Context,
    currentPosition: MessagePosition,
    private val onPositionChanged: (MessagePosition) -> Unit
) {
    private var messagePosition: MessagePosition = currentPosition

    fun show() {
        val bottomSheetDialog = BottomSheetDialog(context, R.style.RoundedBottomSheetDialog)

        // Remove background dimming so user can see the message while editing
        bottomSheetDialog.window?.setDimAmount(0f)

        val bottomSheetView = LayoutInflater.from(context).inflate(
            R.layout.bottom_sheet_position,
            null
        )
        bottomSheetDialog.setContentView(bottomSheetView)

        // Configure the bottom sheet behavior after setting content
        bottomSheetDialog.setOnShowListener { dialog ->
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.isDraggable = true

                // Set background to transparent so rounded corners from our layout show
                sheet.setBackgroundResource(android.R.color.transparent)

                // Enable drag handle on the dialog itself
                d.behavior.isDraggable = true
            }
        }

        setupViews(bottomSheetView, bottomSheetDialog)
        bottomSheetDialog.show()
    }

    private fun setupViews(view: View, dialog: BottomSheetDialog) {
        val buttonTop = view.findViewById<LinearLayout>(R.id.buttonTop)
        val buttonCenter = view.findViewById<LinearLayout>(R.id.buttonCenter)
        val buttonBottom = view.findViewById<LinearLayout>(R.id.buttonBottom)

        // Helper function to update button selection state
        fun updateButtonSelection(position: MessagePosition) {
            messagePosition = position
            buttonTop.isSelected = position == MessagePosition.TOP
            buttonCenter.isSelected = position == MessagePosition.CENTER
            buttonBottom.isSelected = position == MessagePosition.BOTTOM
        }

        // Set initial selection
        updateButtonSelection(messagePosition)

        // Set click listeners for position buttons - apply immediately and dismiss
        buttonTop.setOnClickListener {
            updateButtonSelection(MessagePosition.TOP)
            onPositionChanged(MessagePosition.TOP)
            dialog.dismiss()
        }

        buttonCenter.setOnClickListener {
            updateButtonSelection(MessagePosition.CENTER)
            onPositionChanged(MessagePosition.CENTER)
            dialog.dismiss()
        }

        buttonBottom.setOnClickListener {
            updateButtonSelection(MessagePosition.BOTTOM)
            onPositionChanged(MessagePosition.BOTTOM)
            dialog.dismiss()
        }
    }
}

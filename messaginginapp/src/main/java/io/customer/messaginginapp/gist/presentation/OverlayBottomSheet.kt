package io.customer.messaginginapp.gist.presentation

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.core.graphics.toColorInt
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.skydoves.colorpickerview.listeners.ColorListener
import io.customer.messaginginapp.R

class OverlayBottomSheet(
    private val context: Context,
    currentOverlayColor: String?,
    private val onOverlayChanged: (combinedColor: String?) -> Unit
) {
    // Extract color and opacity from the current overlay color (#RRGGBBAA format)
    private var overlayColor: String?
    private var overlayOpacity: Int
    private var isColorInputProgrammatic = false // Flag to track programmatic text updates

    // Preset color palette (3 vibrant colors)
    private val presetColorPalette = listOf(
        "#FF0000", // Red
        "#4CAF50", // Green
        "#FFC107" // Amber
    )

    init {
        if (currentOverlayColor != null && currentOverlayColor.length == 9 && currentOverlayColor.startsWith("#")) {
            // Extract RGB part (without alpha)
            overlayColor = currentOverlayColor.substring(0, 7)
            // Extract alpha and convert to percentage (0-100)
            val alphaHex = currentOverlayColor.substring(7, 9)
            val alpha = alphaHex.toIntOrNull(16) ?: 255
            overlayOpacity = (alpha * 100 / 255)
        } else if (currentOverlayColor != null && currentOverlayColor.length >= 6) {
            // Color without alpha channel
            overlayColor = if (currentOverlayColor.startsWith("#")) {
                currentOverlayColor.substring(0, 7)
            } else {
                "#${currentOverlayColor.substring(0, 6)}"
            }
            overlayOpacity = 100
        } else {
            // No color provided, default to black
            overlayColor = "#000000"
            overlayOpacity = 100
        }
    }

    private fun getCombinedColor(): String? {
        // Use black as default if no color is set
        val baseColor = overlayColor ?: "#000000"

        // Convert opacity percentage (0-100) to alpha hex (00-FF)
        val alpha = (overlayOpacity * 255 / 100).coerceIn(0, 255)
        val alphaHex = alpha.toString(16).padStart(2, '0').uppercase()

        // Ensure color starts with # and is 6 characters (RGB)
        val colorHex = if (baseColor.startsWith("#")) {
            baseColor.substring(1, minOf(7, baseColor.length)).padEnd(6, '0')
        } else {
            baseColor.substring(0, minOf(6, baseColor.length)).padEnd(6, '0')
        }

        // Combine color and alpha in #RRGGBBAA format
        return "#$colorHex$alphaHex"
    }

    // Helper function to update color consistently everywhere
    private fun applyColor(
        newColor: String,
        colorInput: TextInputEditText,
        circleColors: MutableList<Int>,
        updateCircles: () -> Unit,
        updateCircle1: Boolean = true,
        updateTextInput: Boolean = true
    ) {
        overlayColor = newColor.uppercase()

        // Update circle 1 if requested
        if (updateCircle1) {
            val newColorInt = try {
                overlayColor?.toColorInt() ?: Color.BLACK
            } catch (_: Exception) {
                Color.BLACK
            }
            circleColors[0] = newColorInt
        }

        // Update text input with flag to prevent recursive updates (only if requested)
        if (updateTextInput) {
            isColorInputProgrammatic = true
            colorInput.setText(overlayColor?.removePrefix("#") ?: "")
            isColorInputProgrammatic = false
        }

        // Update circle visuals
        updateCircles()

        // Apply color change
        onOverlayChanged(getCombinedColor())
    }

    fun show() {
        val bottomSheetDialog = BottomSheetDialog(context, R.style.RoundedBottomSheetDialog)

        // Remove background dimming so user can see the message while editing
        bottomSheetDialog.window?.setDimAmount(0f)

        val bottomSheetView = LayoutInflater.from(context).inflate(
            R.layout.bottom_sheet_overlay,
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
        val bottomSheetView = view // Reference to the entire bottom sheet view for dimming
        view.findViewById<View>(R.id.overlayInputsContainer)
        val overlayColorInput = view.findViewById<TextInputEditText>(R.id.overlayColorInput)
        val overlayOpacityInput = view.findViewById<TextInputEditText>(R.id.overlayOpacityInput)
        view.findViewById<View>(R.id.colorPaletteContainer)
        val colorCircle1Frame = view.findViewById<View>(R.id.colorCircle1Frame)
        val colorCircle1 = view.findViewById<View>(R.id.colorCircle1)
        val colorCircle2Frame = view.findViewById<View>(R.id.colorCircle2Frame)
        val colorCircle2 = view.findViewById<View>(R.id.colorCircle2)
        val colorCircle3Frame = view.findViewById<View>(R.id.colorCircle3Frame)
        val colorCircle3 = view.findViewById<View>(R.id.colorCircle3)
        val colorCircle4Frame = view.findViewById<View>(R.id.colorCircle4Frame)
        val colorCircle4 = view.findViewById<View>(R.id.colorCircle4)
        val colorCircleAddFrame = view.findViewById<View>(R.id.colorCircleAddFrame)
        val opacitySlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.opacitySlider)

        val colorCircles = listOf(colorCircle1, colorCircle2, colorCircle3, colorCircle4)
        val colorCircleFrames = listOf(colorCircle1Frame, colorCircle2Frame, colorCircle3Frame, colorCircle4Frame)

        var selectedColorIndex = 0 // Track which palette color is selected (default to first = current color)
        val circleColors = mutableListOf<Int>() // Track the color of each circle

        // Helper function to create circular background with optional selection border
        fun createCircleDrawable(color: Int, isSelected: Boolean = false): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                if (isSelected) {
                    val strokeWidth = (4 * context.resources.displayMetrics.density).toInt()
                    setStroke(strokeWidth, "#2196F3".toColorInt())
                } else {
                    // Add subtle border for non-selected circles so white colors are visible
                    val strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                    setStroke(strokeWidth, "#E0E0E0".toColorInt())
                }
            }
        }

        // Helper function to update all circles with current selection
        fun updateCircles() {
            colorCircles.forEachIndexed { index, circle ->
                if (index < circleColors.size) {
                    circle.background = createCircleDrawable(circleColors[index], index == selectedColorIndex)
                }
            }
        }

        // Set initial overlay color and opacity in text inputs
        val colorWithoutHash = overlayColor?.removePrefix("#") ?: ""
        overlayColorInput.setText(colorWithoutHash)
        val overlayOpacityText = overlayOpacity.toString()
        overlayOpacityInput.setText(overlayOpacityText)
        overlayOpacityInput.setSelection(overlayOpacityText.length)
        opacitySlider.value = overlayOpacity.toFloat()

        // Set up color palette circles
        // Circle 1: Current overlay color (dynamic)
        val currentColorInt = try {
            (overlayColor ?: "#000000").toColorInt()
        } catch (_: Exception) {
            Color.BLACK
        }
        circleColors.add(currentColorInt)

        // Circles 2-4: Preset colors
        presetColorPalette.forEach { color ->
            val colorInt = color.toColorInt()
            circleColors.add(colorInt)
        }

        // Initial draw of all circles with selection
        updateCircles()

        // Set up click listeners for palette colors - use frames for larger touch area
        colorCircleFrames.forEachIndexed { index, frame ->
            frame.setOnClickListener {
                selectedColorIndex = index
                // First circle: extract color from circleColors[0], others: use preset
                val newColor = if (index == 0) {
                    // Convert the color stored in circleColors[0] back to hex
                    String.format("#%06X", 0xFFFFFF and circleColors[0])
                } else {
                    presetColorPalette[index - 1]
                }

                // Don't update circle 1 when selecting preset colors (index != 0)
                applyColor(newColor, overlayColorInput, circleColors, ::updateCircles, updateCircle1 = index == 0)
            }
        }

        // Color picker button opens the full color picker in a separate dialog - use frame for larger touch area
        colorCircleAddFrame.setOnClickListener {
            showColorPickerDialog(dialog, overlayColorInput, circleColors) {
                // Update selection to first circle when color is picked
                selectedColorIndex = 0
                updateCircles()
            }
        }

        // Set up slider with touch listener to dim UI when sliding
        opacitySlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // Dim entire bottom sheet and slider when user starts sliding
                bottomSheetView.alpha = 0.3f
                slider.alpha = 0.3f
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // Restore bottom sheet and slider opacity
                bottomSheetView.alpha = 1.0f
                slider.alpha = 1.0f
            }
        })

        // Apply opacity changes in real-time during sliding
        opacitySlider.addOnChangeListener { _, value, _ ->
            overlayOpacity = value.toInt()
            val opacityText = overlayOpacity.toString()
            overlayOpacityInput.setText(opacityText)
            overlayOpacityInput.setSelection(opacityText.length)
            onOverlayChanged(getCombinedColor())
        }

        // Add text change listener for color input
        overlayColorInput.addTextChangedListener(object : android.text.TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdating || isColorInputProgrammatic) return

                val colorText = s?.toString() ?: ""
                val upperCaseText = colorText.uppercase()

                // Validate hex color (6 characters, valid hex) - use uppercase version
                if (upperCaseText.length == 6 && upperCaseText.matches(Regex("^[0-9A-F]{6}$"))) {
                    // Convert to uppercase if needed
                    if (colorText != upperCaseText) {
                        isUpdating = true
                        overlayColorInput.setText(upperCaseText)
                        overlayColorInput.setSelection(upperCaseText.length)
                        isUpdating = false
                    }

                    val newColor = "#$upperCaseText"
                    // Don't update text input since user just typed it - only update color, circles, and apply
                    applyColor(newColor, overlayColorInput, circleColors, ::updateCircles, updateCircle1 = true, updateTextInput = false)
                }
            }
        })

        // Add text change listener for opacity input
        overlayOpacityInput.addTextChangedListener(object : android.text.TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdating) return

                val opacityText = s?.toString() ?: ""
                var newOpacity = opacityText.toIntOrNull()

                // Clamp any value > 100 to 100
                if (newOpacity != null && newOpacity > 100) {
                    isUpdating = true
                    overlayOpacityInput.setText("100")
                    overlayOpacityInput.setSelection(3)
                    newOpacity = 100 // Update the value to 100
                    isUpdating = false
                    // Continue to apply the clamped value below
                }

                if (newOpacity != null && newOpacity in 0..100 && newOpacity != overlayOpacity) {
                    overlayOpacity = newOpacity
                    opacitySlider.value = newOpacity.toFloat()
                    onOverlayChanged(getCombinedColor())
                }
            }
        })
    }

    private fun showColorPickerDialog(
        parentDialog: BottomSheetDialog,
        colorInput: TextInputEditText,
        circleColors: MutableList<Int>,
        onColorSelected: () -> Unit
    ) {
        // Create a full-screen dialog for the color picker
        val pickerDialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        pickerDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Inflate the color picker layout
        val pickerView = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        pickerDialog.setContentView(pickerView)

        val colorPickerContainer = pickerView.findViewById<android.widget.FrameLayout>(R.id.colorPickerContainer)
        val dimmingOverlay = pickerView.findViewById<View>(R.id.dimmingOverlay)

        // Get initial color
        val initialColorInt = try {
            overlayColor?.toColorInt() ?: Color.WHITE
        } catch (_: Exception) {
            Color.WHITE
        }

        var isLongPress = false
        var isDragging = false
        val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val longPressRunnable = Runnable {
            isLongPress = true
            // Show dimming on long press with fade-in animation
            dimmingOverlay.visibility = View.VISIBLE
            dimmingOverlay.alpha = 0f
            dimmingOverlay.animate().alpha(1f).setDuration(200).start()
        }

        // Build ColorPickerView with built-in HSV palette (using default selector size)
        val colorPickerView = com.skydoves.colorpickerview.ColorPickerView.Builder(context)
            .setActionMode(com.skydoves.colorpickerview.ActionMode.ALWAYS)
            .setColorListener(object : ColorListener {
                override fun onColorSelected(color: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val hexColor = String.format("#%06X", 0xFFFFFF and color)
                        overlayColor = hexColor
                        onOverlayChanged(getCombinedColor())
                        // Update the first color in the list
                        circleColors[0] = color
                    }
                }
            })
            .build()

        // Add to container
        colorPickerContainer.addView(
            colorPickerView,
            0,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Set palette and select color after view is laid out
        colorPickerView.post {
            colorPickerView.setHsvPaletteDrawable()
            colorPickerView.selectByHsvColor(initialColorInt)
        }

        // Handle touch events for click vs long press
        colorPickerView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isLongPress = false
                    isDragging = false
                    longPressHandler.postDelayed(longPressRunnable, 100) // 100ms for long press
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    // If we're in long press mode and moving, dim the picker UI
                    if (isLongPress && !isDragging) {
                        isDragging = true
                        colorPickerContainer.alpha = 0.3f
                    }
                }

                android.view.MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)

                    // Restore picker UI alpha if we were dragging
                    if (isDragging) {
                        colorPickerContainer.alpha = 1.0f
                        isDragging = false
                    }

                    if (!isLongPress) {
                        // Quick tap - dismiss after a short delay to allow ColorListener to fire
                        longPressHandler.postDelayed({
                            pickerDialog.dismiss()
                        }, 50)
                    } else {
                        // Auto-dismiss after long press ends
                        dimmingOverlay.visibility = View.GONE
                        isLongPress = false
                        pickerDialog.dismiss()
                    }
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)

                    // Restore picker UI alpha if we were dragging
                    if (isDragging) {
                        colorPickerContainer.alpha = 1.0f
                        isDragging = false
                    }

                    // Clean up on cancel
                    if (isLongPress) {
                        dimmingOverlay.visibility = View.GONE
                        isLongPress = false
                    }
                }
            }
            false
        }

        // Tap outside (on dimming overlay) to dismiss when in long press mode
        dimmingOverlay.setOnClickListener {
            if (isLongPress) {
                // Restore picker alpha if needed
                if (isDragging) {
                    colorPickerContainer.alpha = 1.0f
                    isDragging = false
                }
                dimmingOverlay.visibility = View.GONE
                isLongPress = false
                pickerDialog.dismiss()
            }
        }

        // Hide parent dialog and show picker dialog
        parentDialog.hide()
        pickerDialog.setOnDismissListener {
            // When picker is dismissed, ensure color is applied consistently
            // overlayColor is already updated by ColorListener, just need to sync UI
            val currentColor = overlayColor ?: "#000000"

            // Set flag to prevent text listener from triggering during programmatic update
            isColorInputProgrammatic = true
            colorInput.setText(currentColor.removePrefix("#"))
            isColorInputProgrammatic = false

            // Update circleColors[0] with the latest color
            val latestColorInt = try {
                currentColor.toColorInt()
            } catch (_: Exception) {
                Color.BLACK
            }
            circleColors[0] = latestColorInt

            onColorSelected()
            parentDialog.show()
        }
        pickerDialog.show()
    }
}

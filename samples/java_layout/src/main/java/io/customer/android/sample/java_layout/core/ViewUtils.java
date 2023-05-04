package io.customer.android.sample.java_layout.core;

import android.widget.EditText;

import androidx.annotation.NonNull;

public class ViewUtils {
    @NonNull
    public static String getTextTrimmed(@NonNull EditText editText) {
        return editText.getText().toString().trim();
    }

    public static void setTextWithSelectionIfFocused(@NonNull EditText editText, String text) {
        editText.setText(text);
        if (editText.isFocused()) {
            editText.setSelection(text.length());
        }
    }
}

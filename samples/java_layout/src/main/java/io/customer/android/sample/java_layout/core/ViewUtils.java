package io.customer.android.sample.java_layout.core;

import android.text.TextUtils;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;

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

    public static void setError(@NonNull TextInputLayout textInputLayout, @Nullable String error) {
        boolean isErrorEnabled = TextUtils.isEmpty(error);
        textInputLayout.setErrorEnabled(isErrorEnabled);
        textInputLayout.setError(error);
    }
}

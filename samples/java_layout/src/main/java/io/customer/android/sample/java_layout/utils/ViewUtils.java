package io.customer.android.sample.java_layout.utils;

import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.android.sample.java_layout.R;
import io.customer.sdk.CustomerIO;

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
        boolean isErrorEnabled = !TextUtils.isEmpty(error);
        textInputLayout.setErrorEnabled(isErrorEnabled);
        textInputLayout.setError(error);
    }

    public static void setUserAgent(@NonNull TextView textView) {
        String userAgent = String.format(Locale.ENGLISH,
                "%s - SDK v%s - App v%s (%s)",
                textView.getContext().getString(R.string.app_name),
                CustomerIO.instance().getSdkVersion(),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE);
        textView.setText(userAgent);
    }
}

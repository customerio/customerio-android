package io.customer.android.sample.java_layout.utils;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.buildinfo.BuildInfoMetadata;

public class ViewUtils {
    public static void prepareForAutomatedTests(@NonNull View view, @StringRes int contentDescResId) {
        view.setContentDescription(view.getContext().getString(contentDescResId));
    }

    public static void prepareForAutomatedTests(@NonNull Toolbar toolbar) {
        toolbar.setNavigationContentDescription(R.string.acd_back_button_icon);
    }

    @NonNull
    public static String getText(@NonNull EditText editText) {
        return editText.getText().toString();
    }

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

    public static void setBuildInfo(@NonNull TextView textView) {
        BuildInfoMetadata buildInfo = new BuildInfoMetadata();
        textView.setText(buildInfo.toFormattedString());
    }

    @NonNull
    public static MaterialAlertDialogBuilder createAlertDialog(@NonNull Activity activity) {
        return new MaterialAlertDialogBuilder(activity)
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok, null);
    }

    public static void clearErrorWhenTextedEntered(@NonNull TextInputEditText editText,
                                                   @NonNull TextInputLayout textInputLayout) {
        editText.addTextChangedListener(new DefaultTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                textInputLayout.setError(null);
                textInputLayout.setErrorEnabled(false);
            }
        });
    }
}

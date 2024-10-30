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

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.android.sample.java_layout.R;

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
        String buildInfo = String.format(
                Locale.ENGLISH,
                "SDK version: %s\n" +
                        "Build date: %s\n" +
                        "Branch: %s\n" +
                        "Default workspace: Native iOS & Android\n" +
                        "App version: %s",
                getSdkVersion(),
                getBuildTime(),
                getBranchName(),
                BuildConfig.VERSION_CODE
        );
        textView.setText(buildInfo);
    }

    private static String getBuildTime() {
        return DateFormat.getDateTimeInstance().format(new Date(BuildConfig.BUILD_TIMESTAMP));
    }

    private static String getSdkVersion() {
        if (isEmptyOrUnset(BuildConfig.SDK_VERSION)) return "as source code";
        return BuildConfig.SDK_VERSION;
    }

    private static String getBranchName() {
        if (isEmptyOrUnset(BuildConfig.BRANCH)) return "local development";
        return BuildConfig.BRANCH + "." + BuildConfig.COMMIT;
    }

    private static boolean isEmptyOrUnset(String text) {
        // When local properties are not set, they have a string value of "null"
        return TextUtils.isEmpty(text) || "null".equalsIgnoreCase(text);
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

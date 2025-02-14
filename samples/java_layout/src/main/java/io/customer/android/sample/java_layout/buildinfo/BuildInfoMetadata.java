package io.customer.android.sample.java_layout.buildinfo;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;

import java.util.Locale;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.sdk.Version;

/**
 * Contains metadata about the build environment.
 */
public class BuildInfoMetadata {
    private final String sdkVersion;
    private final String appVersion;
    private final String buildDate;
    private final String gitMetadata;
    private final String defaultWorkspace;
    private final String language;
    private final String uiFramework;
    private final String sdkIntegration;

    public BuildInfoMetadata() {
        this.sdkVersion = BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.SDK_VERSION, () -> String.format(
                Locale.ENGLISH, "%s-%s",
                Version.version,
                BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.COMMITS_AHEAD_COUNT, () -> "as-source")));
        this.appVersion = String.valueOf(BuildConfig.VERSION_CODE);
        this.buildDate = BuildInfoMetadataUtils.formatBuildDateWithRelativeTime(BuildConfig.BUILD_TIMESTAMP);
        this.gitMetadata = String.format(Locale.ENGLISH, "%s-%s",
                BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.BRANCH_NAME, () -> "development build"),
                BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.COMMIT_HASH, () -> "untracked"));
        this.defaultWorkspace = BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.DEFAULT_WORKSPACE);
        this.language = BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.LANGUAGE);
        this.uiFramework = BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.UI_FRAMEWORK);
        this.sdkIntegration = BuildInfoMetadataUtils.resolveValidOrElse(BuildConfig.SDK_INTEGRATION);
    }

    @NonNull
    @Override
    public String toString() {
        return toFormattedString().toString();
    }

    @NonNull
    public CharSequence toFormattedString() {
        SpannableStringBuilder builder = new SpannableStringBuilder();

        appendBold(builder, "SDK Version: ");
        builder.append(sdkVersion).append(" \t");
        appendBold(builder, "App version: ");
        builder.append(appVersion).append("\n");
        appendBold(builder, "Build Date: ");
        builder.append(buildDate).append("\n");
        appendBold(builder, "Branch: ");
        builder.append(gitMetadata).append("\n");
        appendBold(builder, "Default Workspace: ");
        builder.append(defaultWorkspace).append("\n");
        appendBold(builder, "Language: ");
        builder.append(language).append(" \t");
        appendBold(builder, "UI Framework: ");
        builder.append(uiFramework).append("\n");
        appendBold(builder, "SDK Integration: ");
        builder.append(sdkIntegration);

        return builder;
    }

    private void appendBold(@NonNull SpannableStringBuilder builder, @NonNull String text) {
        SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(spannable);
    }
}

package io.customer.android.sample.java_layout.buildinfo;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class BuildInfoMetadataUtils {
    public static String resolveValidOrElse(@Nullable String text) {
        return resolveValidOrElse(text, () -> "unknown");
    }

    public static String resolveValidOrElse(@Nullable String text, @NonNull ValueProvider<String> fallbackProvider) {
        // When local properties are not set, they have a string value of "null"
        if (!TextUtils.isEmpty(text) && !"null".equalsIgnoreCase(text)) {
            return text;
        }

        return fallbackProvider.get();
    }

    public static String formatBuildDateWithRelativeTime(long buildTimestamp) {
        Date buildDate = new Date(buildTimestamp);
        String formattedDate = DateFormat.getDateTimeInstance().format(buildDate);

        long diffInMillis = System.currentTimeMillis() - buildTimestamp;
        long daysAgo = TimeUnit.MILLISECONDS.toDays(diffInMillis);
        String relativeTime = daysAgo == 0 ? "(Today)" : String.format(Locale.ENGLISH, "(%d days ago)", daysAgo);

        return String.format(Locale.ENGLISH, "%s %s", formattedDate, relativeTime);
    }

    /**
     * Provides a value when the original value is not valid using lambda expressions.
     * This can be simplified with Java 8+ or Kotlin later if needed.
     */
    public interface ValueProvider<T> {
        @NonNull
        T get();
    }
}

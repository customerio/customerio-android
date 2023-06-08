package io.customer.android.sample.java_layout.utils;

import android.text.TextUtils;

import androidx.annotation.Nullable;

public class StringUtils {
    @Nullable
    public static Double parseDouble(@Nullable String value, @Nullable Double defaultValue) {
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static String fromDouble(@Nullable Double value) {
        return value == null ? null : value.toString();
    }

    @Nullable
    public static Integer parseInteger(@Nullable String value, @Nullable Integer defaultValue) {
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static String fromInteger(@Nullable Integer value) {
        return value == null ? null : value.toString();
    }

    public static Boolean parseBoolean(@Nullable String value, Boolean defaultValue) {
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        try {
            return Boolean.valueOf(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static String fromBoolean(@Nullable Boolean value) {
        return value == null ? null : Boolean.toString(value);
    }
}

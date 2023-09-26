package io.customer.android.sample.java_layout.utils;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.Locale;

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
        if (value == null) return null;
        if (value % 1.0 != 0.0) return value.toString();
        else return String.format(Locale.ENGLISH, "%.0f", value);
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

    public static <T extends Enum<T>> T parseEnum(@Nullable String value, T defaultValue, Class<T> enumClass) {
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }

        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public static <T extends Enum<T>> String fromEnum(@Nullable T value) {
        return value == null ? null : value.name();
    }
}

package io.customer.android.sample.java_layout.utils;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class Logger {
    private static final String TAG = "JAVA_LAYOUT";

    void d(@NonNull String message) {
        Log.d(TAG, message);
    }

    void v(@NonNull String message) {
        this.v(message, null);
    }

    void v(@NonNull String message, @Nullable Throwable throwable) {
        Log.v(TAG, message, throwable);
    }

    void e(@NonNull String message) {
        this.e(message, null);
    }

    void e(@NonNull String message, @Nullable Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}

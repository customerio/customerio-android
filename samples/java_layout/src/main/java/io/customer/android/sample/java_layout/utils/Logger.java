package io.customer.android.sample.java_layout.utils;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class Logger {
    private static final String TAG = "JAVA_LAYOUT";

    public void d(@NonNull String message) {
        Log.d(TAG, message);
    }

    public void v(@NonNull String message) {
        this.v(message, null);
    }

    public void v(@NonNull String message, @Nullable Throwable throwable) {
        Log.v(TAG, message, throwable);
    }

    public void e(@NonNull String message) {
        this.e(message, null);
    }

    public void e(@NonNull String message, @Nullable Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}

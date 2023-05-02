package io.customer.android.sample.java_layout.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface Optional<T> {
    static <T> Optional<T> empty() {
        return new OptionalImpl<>(null);
    }

    static <T> Optional<T> of(@NonNull T value) {
        return new OptionalImpl<>(value);
    }

    static <T> Optional<T> ofNullable(@Nullable T value) {
        return new OptionalImpl<>(value);
    }

    boolean isPresent();

    T get();
}

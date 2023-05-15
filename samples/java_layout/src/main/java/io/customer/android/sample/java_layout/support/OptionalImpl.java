package io.customer.android.sample.java_layout.support;

import androidx.annotation.Nullable;

// Can be replaced with [java.util.Optional] once minSdk version is increased to 24
class OptionalImpl<T> implements Optional<T> {
    @Nullable
    private final T value;

    OptionalImpl(@Nullable T value) {
        this.value = value;
    }

    @Override
    public boolean isPresent() {
        return value != null;
    }

    @Override
    public T get() {
        return value;
    }
}

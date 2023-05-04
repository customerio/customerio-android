package io.customer.android.sample.java_layout.data.model;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.core.StringUtils;
import io.customer.android.sample.java_layout.support.Optional;

public class User {
    private static class Keys {
        static final String DISPLAY_NAME = "user_display_name";
        static final String EMAIL = "user_email";
        static final String IS_GUEST = "user_is_guest";
    }

    @NonNull
    public static Optional<User> fromMap(@NonNull Map<String, String> bundle) {
        String email = bundle.get(Keys.EMAIL);
        String displayName = bundle.get(Keys.DISPLAY_NAME);
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(displayName)) {
            return Optional.empty();
        }
        boolean isGuest = StringUtils.parseBoolean(bundle.get(Keys.IS_GUEST), false);
        return Optional.of(new User(email, displayName, isGuest));
    }

    @NonNull
    public static Map<String, String> toMap(@NonNull User user) {
        Map<String, String> bundle = new HashMap<>();
        bundle.put(Keys.EMAIL, user.email);
        bundle.put(Keys.DISPLAY_NAME, user.displayName);
        bundle.put(Keys.IS_GUEST, StringUtils.fromBoolean(user.isGuest));
        return bundle;
    }

    private final String email;
    private final String displayName;
    private final boolean isGuest;

    public User(String email, String displayName, boolean isGuest) {
        this.email = email;
        this.displayName = displayName;
        this.isGuest = isGuest;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public boolean isGuest() {
        return isGuest;
    }
}

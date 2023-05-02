package io.customer.android.sample.java_layout.data.model;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public class User {
    private static final String KEY_EMAIL = "email";
    private static final String KEY_DISPLAY_NAME = "display_name";

    public static String[] keys() {
        return new String[]{KEY_EMAIL, KEY_DISPLAY_NAME};
    }

    @Nullable
    public static User fromMap(Map<String, String> bundle) {
        String email = bundle.get(KEY_EMAIL);
        String displayName = bundle.get(KEY_DISPLAY_NAME);
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(displayName)) {
            return null;
        }
        return new User(email, displayName);
    }

    private final String email;
    private final String displayName;

    public User(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }

    public User(Map<String, String> bundle) {
        this(bundle.get(KEY_EMAIL), bundle.get(KEY_DISPLAY_NAME));
    }

    public Map<String, String> toMap() {
        Map<String, String> bundle = new HashMap<>();
        bundle.put(KEY_EMAIL, email);
        bundle.put(KEY_DISPLAY_NAME, displayName);
        return bundle;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }
}

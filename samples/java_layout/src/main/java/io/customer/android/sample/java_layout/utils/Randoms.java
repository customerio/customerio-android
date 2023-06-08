package io.customer.android.sample.java_layout.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Random class to help generate random values conveniently
 */
public class Randoms {
    private static final String[] eventNames = {
            "Button Click",
            "Random Event",
            "Shopping",
            "Charity"
    };
    private static final int EMAIL_USERNAME_LENGTH = 10;
    // Repeated numbers to increase the probability in random value
    private static final String EMAIL_USERNAME_WHITELIST_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";

    private static int randomInt(final int max) {
        final int min = 0;
        return new Random().nextInt(max - min) + min;
    }

    private static <T> T randomValue(final T[] source) {
        return source[randomInt(source.length)];
    }

    public String email() {
        final int charsLength = EMAIL_USERNAME_WHITELIST_CHARS.length();
        final StringBuilder builder = new StringBuilder(EMAIL_USERNAME_LENGTH);
        for (int i = 0; i < EMAIL_USERNAME_LENGTH; i++) {
            builder.append(EMAIL_USERNAME_WHITELIST_CHARS.charAt(randomInt(charsLength)));
        }
        builder.append("@customer.io");
        return builder.toString();
    }

    public String eventName() {
        return randomValue(eventNames);
    }

    public Map<String, Object> eventAttributes() {
        return new HashMap<String, Object>() {{
            put("color", "Orange");
            put("size", 30);
            put("price", 2.99);
            put("isNew", true);
        }};
    }
}

package io.customer.android.sample.java_layout.core;

import java.util.Random;

/**
 * Random class to help generate random values conveniently
 */
public class Randoms {
    private static final String[] displayNames = {
            "Java layout on wheels",
            "Android ready to roll"
    };
    private static final String[] emails = {
            "java_layout@android.com",
            "roll@android.com"
    };

    private static int randomInt(final int max) {
        final int min = 0;
        return new Random().nextInt(max - min) + min;
    }

    private static <T> T randomValue(final T[] source) {
        return source[randomInt(source.length)];
    }

    public String displayName() {
        return randomValue(displayNames);
    }

    public String email() {
        return randomValue(emails);
    }
}

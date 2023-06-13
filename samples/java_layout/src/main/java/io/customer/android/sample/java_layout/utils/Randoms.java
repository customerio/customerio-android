package io.customer.android.sample.java_layout.utils;

import android.util.Pair;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Random class to help generate random values conveniently
 */
public class Randoms {
    private static final int EMAIL_USERNAME_LENGTH = 10;
    // Repeated numbers to increase the probability in random value
    private static final String EMAIL_USERNAME_WHITELIST_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String[] eventNames = {
            "Order Purchased",
            "movie_watched",
            "appointmentScheduled"
    };

    private static int randomInt(final int max) {
        final int min = 0;
        return new Random().nextInt(max - min) + min;
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

    public Pair<String, Map<String, Object>> trackingEvent() {
        int index = randomInt(eventNames.length);
        Map<String, Object> attributes;
        switch (index) {
            case 1:
                attributes = new HashMap<String, Object>() {{
                    put("movie_name", "The Incredibles");
                }};
                break;
            case 2:
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, 7);
                attributes = new HashMap<String, Object>() {{
                    put("appointmentTime", cal.getTimeInMillis() / 1_000);
                }};
                break;
            case 0:
            default:
                attributes = null;
        }
        return new Pair<>(eventNames[index], attributes);
    }
}

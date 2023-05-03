package io.customer.android.sample.java_layout.core;

import androidx.annotation.Nullable;

import java.util.Map;

public class MapUtils {
    public static <K, V> V getOrElse(@Nullable Map<K, V> map, K key, V fallback) {
        if (map != null && map.containsKey(key)) {
            return map.get(key);
        }
        return fallback;
    }
}

package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.JavaType;


/**
 * Declares one two-level cache: its name, the key version that goes into every Redis key, the value type its
 * Redis serializer is bound to, and how many entries one instance keeps in L1.
 */
public record TwoLevelCacheSpec(
        String name,
        String version,
        JavaType valueType,
        long localMaximumSize
) {
}

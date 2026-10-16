package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


/**
 * Pub/sub payload for cross-instance L1 invalidation. {@code key} is null when the whole cache was cleared.
 * {@code origin} is the publishing instance, which already invalidated its own L1 and skips its own message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheInvalidationMessage(
        String origin,
        String cache,
        String key
) {
}

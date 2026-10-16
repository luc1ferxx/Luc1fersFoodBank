package com.laioffer.onlineorder.controller;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;


/**
 * HTTP caching for the public catalog reads: {@code Cache-Control: max-age=<n>, public} plus a strong ETag
 * derived from the JSON body.
 * <p>
 * Setting Cache-Control here is what overrides Spring Security's default {@code no-cache, no-store}: its
 * cache-control header writer skips responses that already carry Cache-Control (and 304 responses), while the
 * other security headers (CSP, frame options, nosniff, ...) are still written. Spring MVC answers a matching
 * {@code If-None-Match} with 304 and no body.
 * <p>
 * The ETag is a hash of the exact JSON the client receives, so every instance computes the same value for the
 * same catalog regardless of which cache level served it.
 */
@Component
public class CatalogHttpCaching {

    private static final int ETAG_HASH_BYTES = 16;

    private final ObjectMapper objectMapper;
    private final CacheControl cacheControl;


    public CatalogHttpCaching(
            ObjectMapper objectMapper,
            @Value("${app.catalog.http-max-age:30s}") Duration maxAge
    ) {
        this.objectMapper = objectMapper;
        this.cacheControl = CacheControl.maxAge(maxAge).cachePublic();
    }


    public <T> ResponseEntity<T> publicResponse(T body) {
        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .eTag(etag(body))
                .body(body);
    }


    String etag(Object body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return "\"" + HexFormat.of().formatHex(digest, 0, ETAG_HASH_BYTES) + "\"";
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize catalog response for its ETag", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}

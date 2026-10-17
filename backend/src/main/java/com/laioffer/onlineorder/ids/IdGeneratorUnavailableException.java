package com.laioffer.onlineorder.ids;


/**
 * No id can be issued right now: no worker id lease is held, the clock is behind the last id or past the lease's
 * issued-until bound, or the generator was closed. Requests that need an id answer 503 SERVICE_UNAVAILABLE and can be
 * retried.
 */
public class IdGeneratorUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;


    public IdGeneratorUnavailableException(String message) {
        super(message);
    }


    public IdGeneratorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

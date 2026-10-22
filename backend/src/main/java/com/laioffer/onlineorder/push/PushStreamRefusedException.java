package com.laioffer.onlineorder.push;


import org.springframework.http.HttpStatus;


/**
 * A stream that is not opened: 403 for a deactivated merchant or rider, 429 PUSH_STREAM_LIMIT for a login at
 * {@code app.push.max-streams-per-user}, 503 for an instance at {@code app.push.max-streams} or shutting down.
 */
public class PushStreamRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;


    PushStreamRefusedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }


    public HttpStatus status() {
        return status;
    }


    public String code() {
        return code;
    }
}

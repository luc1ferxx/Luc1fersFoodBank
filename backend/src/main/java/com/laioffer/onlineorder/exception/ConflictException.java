package com.laioffer.onlineorder.exception;


import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;


@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {

    public static final String DEFAULT_CODE = "CONFLICT";

    private final String code;


    public ConflictException(String message) {
        this(DEFAULT_CODE, message);
    }


    /**
     * @param code the machine-readable error code returned as {@code code} in the API error body
     */
    public ConflictException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? DEFAULT_CODE : code;
    }


    public String getCode() {
        return code;
    }
}

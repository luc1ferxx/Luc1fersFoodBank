package com.laioffer.onlineorder.exception;


import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;


@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends RuntimeException {

    public static final String DEFAULT_CODE = "BAD_REQUEST";

    private final String code;


    public BadRequestException(String message) {
        this(DEFAULT_CODE, message);
    }


    /**
     * @param code the machine-readable error code returned as {@code code} in the API error body
     */
    public BadRequestException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? DEFAULT_CODE : code;
    }


    public String getCode() {
        return code;
    }
}

package com.moveinsync.rostersync.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class of every error we deliberately return to a caller. Each subclass fixes its HTTP status
 * and a stable error code; the message must be safe to show (no personal data, no internals).
 */
public abstract class ApiException extends RuntimeException {

    private final String code;

    protected ApiException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public abstract HttpStatus status();
}

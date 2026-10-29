package com.moveinsync.rostersync.common.error;

import org.springframework.http.HttpStatus;

/**
 * Also used when a resource exists but belongs to another tenant: answering 404 instead of 403
 * means a caller cannot even learn that another client's employee ID exists.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String resource) {
        super("NOT_FOUND", resource + " not found");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}

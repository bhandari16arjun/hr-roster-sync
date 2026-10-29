package com.moveinsync.rostersync.domain.validation;

/** Answers "does this office code exist for this tenant?". Implemented with a cache in the service layer. */
@FunctionalInterface
public interface OfficeDirectory {
    boolean exists(String tenantId, String officeCode);
}

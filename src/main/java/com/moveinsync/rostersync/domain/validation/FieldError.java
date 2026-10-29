package com.moveinsync.rostersync.domain.validation;

/**
 * One problem with one field of one record. Carries a machine-readable code and a fixed
 * human-readable message - NEVER the rejected value, because that value may be personal data.
 */
public record FieldError(String field, String code, String message) {
}

package com.moveinsync.rostersync.domain;

/**
 * Fields owned by the transport admin. Only {@code TransportService} writes these; HR changes
 * copy them through untouched.
 */
public record TransportFields(String pickupPoint, String specialAssistance) {

    public static final TransportFields EMPTY = new TransportFields(null, null);

    @Override
    public String toString() {
        return "TransportFields[<redacted>]";
    }
}

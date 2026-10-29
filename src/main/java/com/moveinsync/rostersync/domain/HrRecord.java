package com.moveinsync.rostersync.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The fields owned by the HR system. Transport-owned fields (pickup point, special assistance)
 * are deliberately NOT part of this type, so an HR change cannot carry them by construction.
 *
 * Values are normalised on creation (trimmed, email lower-cased, blanks become null) so that the
 * same logical record always produces the same hash.
 */
public record HrRecord(String name, String email, String phone, String managerId,
                       String officeCode, String shiftCode) {

    public HrRecord {
        name = clean(name);
        email = clean(email) == null ? null : clean(email).toLowerCase(Locale.ROOT);
        phone = clean(phone);
        managerId = clean(managerId);
        officeCode = clean(officeCode) == null ? null : clean(officeCode).toUpperCase(Locale.ROOT);
        shiftCode = clean(shiftCode);
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** SHA-256 over the normalised fields. Used for duplicate detection and the nightly diff. */
    public String hash() {
        String canonical = String.join("\u001F",
                n(name), n(email), n(phone), n(managerId), n(officeCode), n(shiftCode));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    /** Names of the HR fields whose value differs from {@code other} (field names only, never values). */
    public List<String> changedFields(HrRecord other) {
        List<String> changed = new ArrayList<>();
        if (other == null) {
            return List.of("name", "email", "phone", "managerId", "officeCode", "shiftCode");
        }
        if (!Objects.equals(name, other.name)) changed.add("name");
        if (!Objects.equals(email, other.email)) changed.add("email");
        if (!Objects.equals(phone, other.phone)) changed.add("phone");
        if (!Objects.equals(managerId, other.managerId)) changed.add("managerId");
        if (!Objects.equals(officeCode, other.officeCode)) changed.add("officeCode");
        if (!Objects.equals(shiftCode, other.shiftCode)) changed.add("shiftCode");
        return changed;
    }

    /** Never print personal data, even by accident in a log line. */
    @Override
    public String toString() {
        return "HrRecord[officeCode=" + officeCode + ", shiftCode=" + shiftCode + ", <personal fields redacted>]";
    }
}

package com.moveinsync.rostersync.domain;

import com.moveinsync.rostersync.domain.validation.FieldError;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecordValidatorTest {

    private final RecordValidator validator = new RecordValidator(
            (tenant, code) -> "acme".equals(tenant) && Set.of("BLR-01", "HYD-01").contains(code));

    private static HrRecord rec(String email, String phone, String office) {
        return new HrRecord("Asha Rao", email, phone, "M1", office, "S1");
    }

    @Test
    void validRecordHasNoErrors() {
        assertThat(validator.validateRecord("acme", rec("a@acme.com", "+919800000001", "BLR-01"))).isEmpty();
    }

    @Test
    void malformedPhoneIsRejected() {
        assertThat(codes(validator.validateRecord("acme", rec("a@acme.com", "98-OOPS", "BLR-01"))))
                .containsExactly("INVALID_PHONE");
    }

    @Test
    void unknownOfficeIsRejected() {
        assertThat(codes(validator.validateRecord("acme", rec("a@acme.com", null, "XXX-99"))))
                .containsExactly("UNKNOWN_OFFICE");
    }

    @Test
    void officeOfAnotherTenantIsUnknown() {
        assertThat(codes(validator.validateRecord("globex", rec("a@acme.com", null, "BLR-01"))))
                .containsExactly("UNKNOWN_OFFICE");
    }

    @Test
    void allProblemsAreReportedAtOnce() {
        assertThat(codes(validator.validateRecord("acme", new HrRecord(null, "bad", "1", null, null, null))))
                .containsExactlyInAnyOrder("REQUIRED", "INVALID_EMAIL", "INVALID_PHONE", "REQUIRED");
    }

    @Test
    void identityRules() {
        assertThat(codes(validator.validateIdentity("E 1", 0L))).containsExactly("INVALID_EMPLOYEE_ID", "INVALID_VERSION");
        assertThat(validator.validateIdentity("E-100_x", 3L)).isEmpty();
    }

    @Test
    void errorMessagesNeverContainTheRejectedValue() {
        List<FieldError> errors = validator.validateRecord("acme", rec("secret.person@private.com.x", "555-SECRET", "BLR-01"));
        assertThat(errors).allSatisfy(e -> assertThat(e.message()).doesNotContain("secret", "555"));
    }

    private static List<String> codes(List<FieldError> errors) {
        return errors.stream().map(FieldError::code).toList();
    }
}

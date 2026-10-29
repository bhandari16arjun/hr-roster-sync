package com.moveinsync.rostersync.api;

import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.SnapshotCsv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end through HTTP: authentication, roles, tenant isolation, error format, and no PII in logs. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiSecurityAndErrorsTest extends IntegrationTest {

    private static final String SECRET_EMAIL = "priya.secret@acme.com";
    private static final String SECRET_PHONE = "+919812345678";

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder b, String user) {
        return b.with(httpBasic(user, user + "-pass"));
    }

    private static String event(String type, String id, long v, String office, String email, String phone) {
        return String.format("{\"type\":\"%s\",\"employeeId\":\"%s\",\"version\":%d,\"name\":\"Priya Sharma\","
                + "\"email\":\"%s\",\"phone\":\"%s\",\"managerId\":\"M1\",\"officeCode\":\"%s\",\"shiftCode\":\"S1\"}",
                type, id, v, email, phone, office);
    }

    private void createE1001() throws Exception {
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("CREATE", "E1001", 1, "BLR-01", SECRET_EMAIL, SECRET_PHONE) + "]}"))
                .andExpect(status().isOk());
    }

    @Test
    void oneBadRecordNeverFailsTheBatch() throws Exception {
        String body = "{\"events\":["
                + event("CREATE", "E1", 1, "BLR-01", "a@acme.com", "+919800000001") + ","
                + event("CREATE", "E2", 1, "BLR-01", "b@acme.com", "12-bad") + ","          // malformed phone
                + event("CREATE", "E3", 1, "XXX-99", "c@acme.com", "+919800000003") + ","   // unknown office
                + event("CREATE", "E4", 1, "HYD-01", "d@acme.com", "+919800000004") + "]}";
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(4))
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PHONE"))
                .andExpect(jsonPath("$.errors[1].code").value("UNKNOWN_OFFICE"))
                .andExpect(jsonPath("$.results[3].outcome").value("APPLIED"));
    }

    @Test
    void hrEventCannotSetTransportFieldsAndGetsAWarning() throws Exception {
        createE1001();
        mvc.perform(asUser(put("/api/v1/employees/E1001/transport"), "acme-transport")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"Gate 3\"}"))
                .andExpect(status().isOk());
        String hr = "{\"events\":[{\"type\":\"UPDATE\",\"employeeId\":\"E1001\",\"version\":2,\"name\":\"Priya Sharma\","
                + "\"email\":\"p@acme.com\",\"officeCode\":\"BLR-02\",\"pickupPoint\":\"HR says Gate 9\"}]}";
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON).content(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.warnings[0].code").value("FIELD_NOT_OWNED"));
        mvc.perform(asUser(get("/api/v1/employees/E1001"), "acme-transport"))
                .andExpect(jsonPath("$.officeCode").value("BLR-02"))
                .andExpect(jsonPath("$.pickupPoint").value("Gate 3"));
    }

    @Test
    void anotherTenantCannotReadOrEditOurEmployee() throws Exception {
        createE1001();
        mvc.perform(asUser(get("/api/v1/employees/E1001"), "globex-transport"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(asUser(put("/api/v1/employees/E1001/transport"), "globex-transport")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"hijack\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(asUser(get("/api/v1/employees/E1001"), "acme-transport"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickupPoint").doesNotExist());
    }

    @Test
    void anotherTenantCannotSeeOurReconciliationRuns() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv",
                new SnapshotCsv().row("E1", 1, "BLR-01", "S1").bytes());
        String json = mvc.perform(asUser(multipart("/api/v1/hr/snapshots").file(file), "acme-hr"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        String runId = json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(asUser(get("/api/v1/reconciliations/" + runId), "acme-ops")).andExpect(status().isOk());
        mvc.perform(asUser(get("/api/v1/reconciliations/" + runId), "globex-ops")).andExpect(status().isNotFound());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        createE1001();
        // HR cannot write transport-owned fields
        mvc.perform(asUser(put("/api/v1/employees/E1001/transport"), "acme-hr")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // transport admin cannot push HR events
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-transport").contentType(MediaType.APPLICATION_JSON)
                .content("{\"events\":[]}")).andExpect(status().isForbidden());
        // only operators approve braked runs
        mvc.perform(asUser(post("/api/v1/reconciliations/00000000-0000-0000-0000-000000000000/approve"), "acme-hr"))
                .andExpect(status().isForbidden());
        // an employee sees only their own record, and cannot list others
        mvc.perform(asUser(get("/api/v1/me"), "acme-e1001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("E1001"));
        mvc.perform(asUser(get("/api/v1/employees/E1001"), "acme-e1001")).andExpect(status().isForbidden());
        // no credentials / wrong password
        mvc.perform(get("/api/v1/employees/E1001")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/employees/E1001").with(httpBasic("acme-ops", "wrong")))
                .andExpect(status().isUnauthorized());
        // metrics are for operators only, health is public
        mvc.perform(asUser(get("/actuator/prometheus"), "acme-hr")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void errorsAreStructuredAndNeverEchoTheInput() throws Exception {
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\": [ {\"email\": \"" + SECRET_EMAIL + "\" "))   // broken JSON
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET_EMAIL));

        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[" + event("CREATE", "E9", 1, "BLR-01", "not-an-email", SECRET_PHONE + "x") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[*].code", hasItem("INVALID_EMAIL")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("not-an-email", SECRET_PHONE));
    }

    @Test
    void personalDataNeverAppearsInLogs(CapturedOutput output) throws Exception {
        createE1001();
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON)
                .content("{\"events\":[" + event("UPDATE", "E1001", 1, "HYD-01", SECRET_EMAIL, SECRET_PHONE) + "," // conflict
                        + event("UPDATE", "E1002", 1, "BLR-01", "broken@", SECRET_PHONE) + "]}"));          // invalid
        mvc.perform(asUser(post("/api/v1/hr/events"), "acme-hr").contentType(MediaType.APPLICATION_JSON)
                .content("{\"events\":[{\"type\":\"DEACTIVATE\",\"employeeId\":\"E1001\",\"version\":3}]}"));

        assertThat(output.getAll()).contains("E1001");               // IDs are logged ...
        assertThat(output.getAll()).doesNotContain(SECRET_EMAIL, SECRET_PHONE, "Priya", "broken@"); // ... PII is not
    }

    @Test
    void requestIdIsEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "demo-req-12345"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("demo-req-12345"));
    }
}

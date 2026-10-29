package com.moveinsync.rostersync.support;

import com.moveinsync.rostersync.domain.ChangeSource;
import com.moveinsync.rostersync.domain.HrChange;
import com.moveinsync.rostersync.domain.HrRecord;
import com.moveinsync.rostersync.ops.FaultInjector;
import com.moveinsync.rostersync.ops.SimulatedCrashException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

/** Base for tests that need the full Spring context and a real PostgreSQL. */
@SpringBootTest
public abstract class IntegrationTest {

    public static final String ACME = "acme";
    public static final String GLOBEX = "globex";

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected FaultInjector faults;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.datasource.url", EmbeddedPg::jdbcUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("app.outbox.relay-enabled", () -> "false");   // tests drive the relay explicitly
        r.add("app.fault.enabled", () -> "true");
        r.add("logging.structured.format.console", () -> "");
        Path work = Files.createTempDirectory("recon-test");
        r.add("app.reconciliation.work-dir", work::toString);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE employee, outbox_event, processed_event, booking, reconciliation_run, "
                + "reconciliation_missing, reconciliation_error RESTART IDENTITY");
        faults.disarmAll();
        faults.setCrasher(point -> {
            throw new SimulatedCrashException(point);
        });
    }

    @AfterEach
    void disarm() {
        faults.disarmAll();
    }

    protected static HrRecord rec(String office, String shift) {
        return new HrRecord("Test Person", "test.person@acme.com", "+919800000001", "M1", office, shift);
    }

    protected static HrChange upsert(String id, long v, String office, String shift) {
        return HrChange.upsert(ACME, id, v, rec(office, shift), ChangeSource.API);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}

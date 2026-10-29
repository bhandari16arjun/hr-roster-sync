package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.domain.validation.FieldError;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReconciliationRepository {

    private static final RowMapper<ReconciliationRun> MAPPER = (rs, i) -> new ReconciliationRun(
            rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("status"),
            rs.getInt("file_rows"), (Integer) rs.getObject("declared_rows"), rs.getInt("invalid_rows"),
            rs.getInt("active_before"), rs.getInt("new_count"), rs.getInt("changed_count"),
            rs.getInt("unchanged_count"), rs.getInt("stale_count"), rs.getInt("missing_count"),
            rs.getInt("applied_count"), rs.getInt("deactivated_count"), rs.getString("brake_reason"),
            (Long) rs.getObject("compare_millis"), ts(rs.getTimestamp("started_at")),
            ts(rs.getTimestamp("finished_at")), rs.getString("decided_by"), ts(rs.getTimestamp("decided_at")));

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws DuplicateKeyException if the tenant already has a RUNNING/APPLYING run (unique partial index). */
    public void insertRunning(UUID id, String tenantId, String workDir, Instant now) {
        jdbc.update("INSERT INTO reconciliation_run (id, tenant_id, status, work_dir, started_at) "
                        + "VALUES (:id, :t, 'RUNNING', :w, :now)",
                new MapSqlParameterSource("id", id).addValue("t", tenantId).addValue("w", workDir)
                        .addValue("now", Timestamp.from(now)));
    }

    public Optional<ReconciliationRun> find(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM reconciliation_run WHERE tenant_id = :t AND id = :id",
                new MapSqlParameterSource("t", tenantId).addValue("id", id), MAPPER).stream().findFirst();
    }

    public List<ReconciliationRun> recent(String tenantId, int limit) {
        return jdbc.query("SELECT * FROM reconciliation_run WHERE tenant_id = :t ORDER BY started_at DESC LIMIT :l",
                new MapSqlParameterSource("t", tenantId).addValue("l", limit), MAPPER);
    }

    public String workDir(UUID id) {
        return jdbc.queryForObject("SELECT work_dir FROM reconciliation_run WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    public void saveCompareResult(UUID id, int fileRows, Integer declaredRows, int invalidRows,
                                  BucketComparator.Counts c, long compareMillis) {
        jdbc.update("""
                UPDATE reconciliation_run SET file_rows = :f, declared_rows = :d, invalid_rows = :inv,
                       active_before = :ab, new_count = :n, changed_count = :ch, unchanged_count = :u,
                       stale_count = :s, missing_count = :m, compare_millis = :ms
                WHERE id = :id""",
                new MapSqlParameterSource("id", id).addValue("f", fileRows).addValue("d", declaredRows)
                        .addValue("inv", invalidRows).addValue("ab", c.activeBefore).addValue("n", c.newRows)
                        .addValue("ch", c.changed).addValue("u", c.unchanged).addValue("s", c.stale + c.conflicts)
                        .addValue("m", c.missing).addValue("ms", compareMillis));
    }

    /** Atomic state transition: only succeeds if the run is currently in {@code from}. */
    public boolean transition(UUID id, String from, String to) {
        return jdbc.update("UPDATE reconciliation_run SET status = :to WHERE id = :id AND status = :from",
                new MapSqlParameterSource("id", id).addValue("from", from).addValue("to", to)) == 1;
    }

    public void brake(UUID id, String reason, Instant now) {
        jdbc.update("UPDATE reconciliation_run SET status = 'AWAITING_APPROVAL', brake_reason = :r, finished_at = :now "
                        + "WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("r", reason).addValue("now", Timestamp.from(now)));
    }

    public void complete(UUID id, int applied, int deactivated, Instant now) {
        jdbc.update("UPDATE reconciliation_run SET status = 'COMPLETED', applied_count = :a, deactivated_count = :d, "
                        + "finished_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("a", applied).addValue("d", deactivated)
                        .addValue("now", Timestamp.from(now)));
    }

    public void decide(UUID id, String decidedBy, Instant now) {
        jdbc.update("UPDATE reconciliation_run SET decided_by = :by, decided_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("by", decidedBy).addValue("now", Timestamp.from(now)));
    }

    public void fail(UUID id, String reason, Instant now) {
        jdbc.update("UPDATE reconciliation_run SET status = 'FAILED', brake_reason = :r, finished_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("r", reason).addValue("now", Timestamp.from(now)));
    }

    /** A newer export makes older runs that are still waiting for approval obsolete. */
    public List<UUID> supersedeAwaiting(String tenantId) {
        return jdbc.queryForList("UPDATE reconciliation_run SET status = 'SUPERSEDED' "
                        + "WHERE tenant_id = :t AND status = 'AWAITING_APPROVAL' RETURNING id",
                new MapSqlParameterSource("t", tenantId), UUID.class);
    }

    /** On startup: runs left RUNNING/APPLYING by a crashed process are marked FAILED (re-upload is safe). */
    public List<UUID> failInterrupted(Instant now) {
        return jdbc.queryForList("UPDATE reconciliation_run SET status = 'FAILED', "
                        + "brake_reason = 'interrupted by a restart; re-run the export (it is idempotent)', "
                        + "finished_at = :now WHERE status IN ('RUNNING', 'APPLYING') RETURNING id",
                new MapSqlParameterSource("now", Timestamp.from(now)), UUID.class);
    }

    public void insertMissing(UUID runId, List<Map.Entry<String, Long>> missing) {
        if (missing.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO reconciliation_missing (run_id, employee_id, hr_version) VALUES (:r, :e, :v)",
                missing.stream().map(m -> new MapSqlParameterSource("r", runId).addValue("e", m.getKey())
                        .addValue("v", m.getValue())).toArray(SqlParameterSource[]::new));
    }

    /** Keyset pagination over the missing list (bounded memory). */
    public Map<String, Long> missingPage(UUID runId, String afterEmployeeId, int limit) {
        Map<String, Long> page = new LinkedHashMap<>();
        jdbc.query("SELECT employee_id, hr_version FROM reconciliation_missing WHERE run_id = :r AND employee_id > :after "
                        + "ORDER BY employee_id LIMIT :l",
                new MapSqlParameterSource("r", runId).addValue("after", afterEmployeeId).addValue("l", limit),
                rs -> {
                    page.put(rs.getString(1), rs.getLong(2));
                });
        return page;
    }

    public List<String> missingSample(UUID runId, int limit) {
        return jdbc.queryForList("SELECT employee_id FROM reconciliation_missing WHERE run_id = :r "
                + "ORDER BY employee_id LIMIT :l", new MapSqlParameterSource("r", runId).addValue("l", limit), String.class);
    }

    public void insertErrors(UUID runId, List<RowError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO reconciliation_error (run_id, row_number, employee_id, field, code) "
                        + "VALUES (:r, :n, :e, :f, :c) ON CONFLICT DO NOTHING",
                errors.stream().map(e -> new MapSqlParameterSource("r", runId).addValue("n", e.rowNumber())
                        .addValue("e", e.employeeId()).addValue("f", e.error().field())
                        .addValue("c", e.error().code())).toArray(SqlParameterSource[]::new));
    }

    public List<Map<String, Object>> errors(UUID runId, int limit) {
        return jdbc.queryForList("SELECT row_number, employee_id, field, code FROM reconciliation_error "
                + "WHERE run_id = :r ORDER BY row_number, field LIMIT :l",
                new MapSqlParameterSource("r", runId).addValue("l", limit));
    }

    public record RowError(long rowNumber, String employeeId, FieldError error) {
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

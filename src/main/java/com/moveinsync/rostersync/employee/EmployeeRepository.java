package com.moveinsync.rostersync.employee;

import com.moveinsync.rostersync.domain.Buckets;
import com.moveinsync.rostersync.domain.EmployeeState;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.HrRecord;
import com.moveinsync.rostersync.domain.TransportFields;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * All SQL for the employee table. Every method takes the tenantId and every query filters on it:
 * that is the second line of tenant isolation after authentication.
 *
 * Plain JDBC (not JPA) on purpose: we need SELECT ... FOR UPDATE, INSERT ... ON CONFLICT,
 * batch writes and streaming reads, and explicit SQL is easier to reason about for those.
 */
@Repository
public class EmployeeRepository {

    private static final String COLUMNS = """
            tenant_id, employee_id, status, hr_version, name, email, phone, manager_id, office_code,
            shift_code, pickup_point, special_assistance""";

    private static final RowMapper<EmployeeState> MAPPER = EmployeeRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeState> find(String tenantId, String employeeId) {
        List<EmployeeState> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM employee WHERE tenant_id = :t AND employee_id = :e",
                new MapSqlParameterSource("t", tenantId).addValue("e", employeeId), MAPPER);
        return rows.stream().findFirst();
    }

    /** Locks the rows until the surrounding transaction ends. Sorted to avoid deadlocks between batches. */
    public List<EmployeeState> findAllForUpdate(String tenantId, Collection<String> employeeIds) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM employee WHERE tenant_id = :t AND employee_id IN (:ids) "
                        + "ORDER BY employee_id FOR UPDATE",
                new MapSqlParameterSource("t", tenantId).addValue("ids", employeeIds), MAPPER);
    }

    /**
     * Inserts new employees. ON CONFLICT DO NOTHING handles the race where another transaction
     * inserted the same employee first; the returned array tells the caller which rows lost.
     */
    public int[] insertIfAbsent(List<EmployeeState> states, Instant now) {
        return jdbc.batchUpdate("""
                INSERT INTO employee (tenant_id, employee_id, bucket, status, hr_version, hr_hash, name, email, phone,
                                      manager_id, office_code, shift_code, created_at, updated_at)
                VALUES (:t, :e, :bucket, :status, :version, :hash, :name, :email, :phone,
                        :manager, :office, :shift, :now, :now)
                ON CONFLICT (tenant_id, employee_id) DO NOTHING""",
                states.stream().map(s -> hrParams(s, now)).toArray(SqlParameterSource[]::new));
    }

    /** Writes only the HR-owned columns. Transport columns are not in this statement at all. */
    public void updateHr(List<EmployeeState> states, Instant now) {
        jdbc.batchUpdate("""
                UPDATE employee SET status = :status, hr_version = :version, hr_hash = :hash, name = :name,
                       email = :email, phone = :phone, manager_id = :manager, office_code = :office,
                       shift_code = :shift, updated_at = :now
                WHERE tenant_id = :t AND employee_id = :e""",
                states.stream().map(s -> hrParams(s, now)).toArray(SqlParameterSource[]::new));
    }

    /** Writes only the transport-owned columns. */
    public int updateTransport(String tenantId, String employeeId, TransportFields fields, String updatedBy, Instant now) {
        return jdbc.update("""
                UPDATE employee SET pickup_point = :pickup, special_assistance = :assist,
                       transport_updated_at = :now, transport_updated_by = :by, updated_at = :now
                WHERE tenant_id = :t AND employee_id = :e""",
                new MapSqlParameterSource("t", tenantId).addValue("e", employeeId)
                        .addValue("pickup", fields.pickupPoint()).addValue("assist", fields.specialAssistance())
                        .addValue("by", updatedBy).addValue("now", Timestamp.from(now)));
    }

    public int countActive(String tenantId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = :t AND status = 'ACTIVE'",
                new MapSqlParameterSource("t", tenantId), Integer.class);
        return n == null ? 0 : n;
    }

    /** Streams the minimal columns of one bucket (row by row, constant memory) for the nightly diff. */
    public void streamBucket(String tenantId, int bucket, Consumer<StoredDigest> consumer) {
        jdbc.query("SELECT employee_id, status, hr_version, hr_hash FROM employee WHERE tenant_id = :t AND bucket = :b",
                new MapSqlParameterSource("t", tenantId).addValue("b", bucket),
                rs -> {
                    consumer.accept(new StoredDigest(rs.getString(1), EmployeeStatus.valueOf(rs.getString(2)),
                            rs.getLong(3), rs.getString(4)));
                });
    }

    /** What the diff needs to know about a stored employee - no personal data. */
    public record StoredDigest(String employeeId, EmployeeStatus status, long hrVersion, String hrHash) {
    }

    private static MapSqlParameterSource hrParams(EmployeeState s, Instant now) {
        HrRecord r = s.hrRecord();
        return new MapSqlParameterSource()
                .addValue("t", s.tenantId())
                .addValue("e", s.employeeId())
                .addValue("bucket", Buckets.of(s.employeeId()))
                .addValue("status", s.status().name())
                .addValue("version", s.hrVersion())
                .addValue("hash", s.hrHash())
                .addValue("name", r == null ? null : r.name())
                .addValue("email", r == null ? null : r.email())
                .addValue("phone", r == null ? null : r.phone())
                .addValue("manager", r == null ? null : r.managerId())
                .addValue("office", r == null ? null : r.officeCode())
                .addValue("shift", r == null ? null : r.shiftCode())
                .addValue("now", Timestamp.from(now));
    }

    private static EmployeeState map(ResultSet rs, int i) throws SQLException {
        String name = rs.getString("name");
        String email = rs.getString("email");
        String office = rs.getString("office_code");
        HrRecord record = (name == null && email == null && office == null) ? null
                : new HrRecord(name, email, rs.getString("phone"), rs.getString("manager_id"), office,
                rs.getString("shift_code"));
        return new EmployeeState(rs.getString("tenant_id"), rs.getString("employee_id"),
                EmployeeStatus.valueOf(rs.getString("status")), rs.getLong("hr_version"), record,
                new TransportFields(rs.getString("pickup_point"), rs.getString("special_assistance")));
    }
}

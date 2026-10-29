package com.moveinsync.rostersync.booking;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class BookingRepository {

    private static final RowMapper<Booking> MAPPER = (rs, i) -> new Booking(rs.getLong("id"),
            rs.getString("employee_id"), rs.getDate("trip_date").toLocalDate(), rs.getString("seat_no"),
            rs.getString("status"), rs.getString("cancel_reason"));

    private final NamedParameterJdbcTemplate jdbc;

    public BookingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String tenantId, String employeeId, LocalDate tripDate, String seatNo) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO booking (tenant_id, employee_id, trip_date, seat_no, status) "
                        + "VALUES (:t, :e, :d, :s, 'CONFIRMED')",
                new MapSqlParameterSource("t", tenantId).addValue("e", employeeId)
                        .addValue("d", Date.valueOf(tripDate)).addValue("s", seatNo), keys, new String[]{"id"});
        return keys.getKey().longValue();
    }

    public List<Booking> findForEmployee(String tenantId, String employeeId) {
        return jdbc.query("SELECT * FROM booking WHERE tenant_id = :t AND employee_id = :e ORDER BY trip_date, id",
                new MapSqlParameterSource("t", tenantId).addValue("e", employeeId), MAPPER);
    }

    /** Cancels all future confirmed bookings and frees their seats. Idempotent by nature. */
    public int cancelFuture(String tenantId, String employeeId, LocalDate fromDate, String reason, Instant now) {
        return jdbc.update("""
                UPDATE booking SET status = 'CANCELLED', seat_no = NULL, cancel_reason = :r, updated_at = :now
                WHERE tenant_id = :t AND employee_id = :e AND trip_date >= :d AND status = 'CONFIRMED'""",
                new MapSqlParameterSource("t", tenantId).addValue("e", employeeId)
                        .addValue("d", Date.valueOf(fromDate)).addValue("r", reason)
                        .addValue("now", Timestamp.from(now)));
    }

    public record Booking(long id, String employeeId, LocalDate tripDate, String seatNo, String status,
                          String cancelReason) {
    }
}

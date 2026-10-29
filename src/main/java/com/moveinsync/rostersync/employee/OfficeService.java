package com.moveinsync.rostersync.employee;

import com.moveinsync.rostersync.common.error.ConflictException;
import com.moveinsync.rostersync.config.CoreBeansConfig;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Office codes per tenant, cached because EVERY validated record looks one up
 * (200,000 lookups per nightly file). See CoreBeansConfig for the eviction policy.
 */
@Service
public class OfficeService implements OfficeDirectory {

    private final NamedParameterJdbcTemplate jdbc;
    private final OfficeService self; // proxied self so @Cacheable applies on internal calls

    public OfficeService(NamedParameterJdbcTemplate jdbc, @Lazy OfficeService self) {
        this.jdbc = jdbc;
        this.self = self;
    }

    @Override
    public boolean exists(String tenantId, String officeCode) {
        return officeCode != null && self.officeCodes(tenantId).contains(officeCode.toUpperCase(Locale.ROOT));
    }

    @Cacheable(cacheNames = CoreBeansConfig.OFFICE_CACHE, key = "#tenantId")
    public Set<String> officeCodes(String tenantId) {
        return Set.copyOf(new HashSet<>(jdbc.queryForList(
                "SELECT office_code FROM office WHERE tenant_id = :t", new MapSqlParameterSource("t", tenantId),
                String.class)));
    }

    public List<Office> list(String tenantId) {
        return jdbc.query("SELECT office_code, name FROM office WHERE tenant_id = :t ORDER BY office_code",
                new MapSqlParameterSource("t", tenantId), (rs, i) -> new Office(rs.getString(1), rs.getString(2)));
    }

    @CacheEvict(cacheNames = CoreBeansConfig.OFFICE_CACHE, key = "#tenantId")
    public Office create(String tenantId, String code, String name) {
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        try {
            jdbc.update("INSERT INTO office (tenant_id, office_code, name) VALUES (:t, :c, :n)",
                    new MapSqlParameterSource("t", tenantId).addValue("c", normalized).addValue("n", name.strip()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("OFFICE_EXISTS", "an office with this code already exists");
        }
        return new Office(normalized, name.strip());
    }

    public record Office(String code, String name) {
    }
}

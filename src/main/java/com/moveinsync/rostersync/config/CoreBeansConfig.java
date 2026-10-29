package com.moveinsync.rostersync.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.rostersync.domain.EmployeeMerger;
import com.moveinsync.rostersync.domain.SafetyBrake;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the plain domain classes (which know nothing about Spring) into the application. */
@Configuration
public class CoreBeansConfig {

    public static final String OFFICE_CACHE = "officeCodes";

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EmployeeMerger employeeMerger() {
        return new EmployeeMerger();
    }

    @Bean
    public RecordValidator recordValidator(OfficeDirectory officeDirectory) {
        return new RecordValidator(officeDirectory);
    }

    @Bean
    public SafetyBrake safetyBrake(AppProperties props) {
        return new SafetyBrake(props.reconciliation().maxMissingRatio(), props.reconciliation().maxInvalidRatio());
    }

    /**
     * Hot data cache: the set of office codes per tenant, read on EVERY record validation.
     * Eviction: entries expire 5 minutes after being loaded (bounded staleness), are evicted
     * immediately on this node when an office is added, and at most 10,000 tenants are kept (LRU-ish).
     */
    @Bean
    public CacheManager cacheManager(AppProperties props) {
        CaffeineCacheManager manager = new CaffeineCacheManager(OFFICE_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(props.cache().officeTtl())
                .maximumSize(10_000)
                .recordStats());
        return manager;
    }
}

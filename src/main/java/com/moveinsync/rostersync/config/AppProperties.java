package com.moveinsync.rostersync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** All tunables in one typed place (bound from the "app" section of application.yml). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Reconciliation reconciliation, Outbox outbox, Cache cache, Fault fault, Security security) {

    public record Reconciliation(String workDir, double maxMissingRatio, double maxInvalidRatio,
                                 int chunkSize, int maxStoredErrors) {
    }

    public record Outbox(boolean relayEnabled, long pollIntervalMs, int batchSize, int maxAttempts, long baseBackoffMs) {
    }

    public record Cache(Duration officeTtl) {
    }

    public record Fault(boolean enabled) {
    }

    public record Security(List<User> users) {
    }

    public record User(String username, String password, String role, String tenant, String employeeId) {
    }
}

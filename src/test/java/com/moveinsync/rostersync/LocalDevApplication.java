package com.moveinsync.rostersync;

import com.moveinsync.rostersync.support.EmbeddedPg;
import org.springframework.boot.SpringApplication;

/**
 * Runs the real application against an embedded PostgreSQL - no Docker, no local database needed.
 *
 *   ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.moveinsync.rostersync.LocalDevApplication
 *
 * (Data is lost when the process stops. Use docker compose for a persistent setup.)
 */
public class LocalDevApplication {

    public static void main(String[] args) {
        System.setProperty("spring.datasource.url", EmbeddedPg.jdbcUrl());
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("app.fault.enabled", System.getProperty("app.fault.enabled", "true"));
        SpringApplication.run(RosterSyncApplication.class, args);
    }
}

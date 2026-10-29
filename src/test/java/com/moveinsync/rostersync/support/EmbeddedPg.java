package com.moveinsync.rostersync.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real PostgreSQL process for the whole test JVM (no Docker needed). Tests run against the
 * same SQL dialect as production: FOR UPDATE, SKIP LOCKED, ON CONFLICT, partial indexes.
 */
public final class EmbeddedPg {

    private static EmbeddedPostgres pg;

    private EmbeddedPg() {
    }

    public static synchronized String jdbcUrl() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // shutting down
                }
            }));
        }
        return pg.getJdbcUrl("postgres", "postgres");
    }
}

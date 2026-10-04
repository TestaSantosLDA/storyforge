package com.storyforge.story;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serializes everything that reads or writes the queue or the in-flight count (Run, add, reorder, restore) with a
 * Postgres transaction-scoped advisory lock, so two Runs can never both see room under the cap (CLAUDE.md rule 7).
 * Must be called inside a transaction; the lock is released at commit or rollback.
 */
@Component
public class QueueLock {

    private static final String KEY = "storyforge.story-queue";

    private final JdbcClient jdbc;

    QueueLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire() {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:key))").param("key", KEY).query().singleRow();
    }
}

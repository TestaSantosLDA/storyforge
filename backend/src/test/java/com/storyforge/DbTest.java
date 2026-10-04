package com.storyforge;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Base for tests against a real Postgres (Testcontainers). Each test starts from empty tables. */
@SpringBootTest(properties = {
        "storyforge.paths.characters=target/test-characters",
        "storyforge.paths.assets=target/test-assets"})
@Import(TestcontainersConfiguration.class)
public abstract class DbTest {

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("truncate entity_change, attempt, status_change, story, topic restart identity cascade").update();
        jdbc.sql("update gpu_lease set holder = null, acquired_at = null, expires_at = null").update();
    }
}

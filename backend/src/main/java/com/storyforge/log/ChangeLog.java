package com.storyforge.log;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Before/after log of topic and story edits (docs/pipeline-a/stage-0 "Logging"). */
@Component
public class ChangeLog {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    ChangeLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String entityType, long entityId, String action, Map<String, ?> before, Map<String, ?> after,
            String triggeredBy) {
        jdbc.sql("""
                insert into entity_change (entity_type, entity_id, action, before_value, after_value, triggered_by)
                values (:type, :id, :action, cast(:before as jsonb), cast(:after as jsonb), :by)
                """)
                .param("type", entityType).param("id", entityId).param("action", action)
                .param("before", before == null ? null : json.writeValueAsString(before))
                .param("after", after == null ? null : json.writeValueAsString(after))
                .param("by", triggeredBy)
                .update();
    }
}

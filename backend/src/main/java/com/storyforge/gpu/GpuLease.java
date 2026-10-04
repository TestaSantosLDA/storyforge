package com.storyforge.gpu;

import com.storyforge.config.StoryforgeProperties;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The GPU lock as a database lease (CLAUDE.md rule 1: no in-memory workflow state). One holder at a time; a lease
 * whose holder crashed simply expires. Pipeline A priority is enforced by callers: Pipeline B only asks when no
 * Pipeline A story is waiting for the GPU.
 */
@Service
public class GpuLease {

    public static final String GPU = "gpu0";

    private final JdbcClient jdbc;
    private final Duration ttl;

    GpuLease(JdbcClient jdbc, StoryforgeProperties props) {
        this.jdbc = jdbc;
        this.ttl = props.gpu().leaseTtl();
    }

    /** Takes the lease if it is free, expired, or already ours (which renews it). */
    public boolean tryAcquire(String holder) {
        return jdbc.sql("""
                update gpu_lease
                   set holder = :holder, acquired_at = now(), expires_at = now() + make_interval(secs => :ttl)
                 where gpu_id = :gpu
                   and (holder is null or holder = :holder or expires_at < now())
                """)
                .param("holder", holder).param("ttl", (double) ttl.toMillis() / 1000).param("gpu", GPU)
                .update() == 1;
    }

    /** Releases the lease if we hold it. Returns false if someone else holds it (ours had expired). */
    public boolean release(String holder) {
        return jdbc.sql("update gpu_lease set holder = null, acquired_at = null, expires_at = null "
                        + "where gpu_id = :gpu and holder = :holder")
                .param("gpu", GPU).param("holder", holder).update() == 1;
    }

    /** On startup nothing can be running yet, so any lease left over from a crash is cleared. */
    public void releaseAll() {
        jdbc.sql("update gpu_lease set holder = null, acquired_at = null, expires_at = null").update();
    }
}

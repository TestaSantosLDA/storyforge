package com.storyforge.gpu;

import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.DbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Pipeline B scenario B0-2 / B2-6: A and B never hold the GPU at once; a crashed holder's lease expires. */
class GpuLeaseTest extends DbTest {

    @Autowired
    GpuLease gpu;

    @Test
    void one_holder_at_a_time() {
        assertThat(gpu.tryAcquire("pipeline-a:story-1")).isTrue();
        assertThat(gpu.tryAcquire("pipeline-b:job-1")).isFalse();
        assertThat(gpu.tryAcquire("pipeline-a:story-1")).as("holder renews").isTrue();

        assertThat(gpu.release("pipeline-b:job-1")).as("non-holder can't release").isFalse();
        assertThat(gpu.release("pipeline-a:story-1")).isTrue();
        assertThat(gpu.tryAcquire("pipeline-b:job-1")).isTrue();
    }

    @Test
    void an_expired_lease_can_be_taken_over() {
        assertThat(gpu.tryAcquire("crashed")).isTrue();
        jdbc.sql("update gpu_lease set expires_at = now() - interval '1 second'").update();

        assertThat(gpu.tryAcquire("pipeline-a:story-2")).isTrue();
        assertThat(gpu.release("crashed")).isFalse();
    }
}

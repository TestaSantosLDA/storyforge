package com.storyforge.clips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.storyforge.DbTest;
import com.storyforge.storage.AssetStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Scenario 7: a video over the maximum length is refused at validation. */
@TestPropertySource(properties = "storyforge.clips.max-source-minutes=1")
class ClipSourceLimitTest extends DbTest {

    @Autowired ClipJobs jobs;
    @Autowired IntakeStep intake;
    @Autowired AssetStorage assets;

    @Test
    void tooLongIsRefusedAtValidation() throws Exception {
        ClipsScenariosTest.makeVideos();
        assumeTrue(ClipsScenariosTest.ffmpegReady, "FFmpeg not available");
        Path video = ClipsScenariosTest.VIDEO;
        String key = "clips/incoming/long.mp4";
        assets.put(key, Files.newInputStream(video));
        long id = jobs.submit(new ClipJobs.Form(null, key, "long.mp4", "my own video", true, "Main channel", 3, null,
                false, null), "user:tester");
        intake.ingest(id);
        assertThat(jobs.get(id).status()).isEqualTo(ClipStatus.NEEDS_ATTENTION);
        assertThat(jobs.get(id).attentionReason()).contains("the limit is 1");
    }
}

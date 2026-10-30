package com.storyforge.pipeline;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.StoryStep;
import com.storyforge.stage2.AudioStep;
import com.storyforge.stage3.VisualsStep;
import com.storyforge.stage4.AssemblyStep;
import com.storyforge.stage4.FinalReview;
import com.storyforge.stage5.PublishStep;
import com.storyforge.story.StoryFormat;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PipelineDispatcherTest {

    StoryStep story = mock(StoryStep.class);
    AudioStep audio = mock(AudioStep.class);
    VisualsStep visuals = mock(VisualsStep.class);
    AssemblyStep assembly = mock(AssemblyStep.class);
    StoryStatusService status = mock(StoryStatusService.class);
    PipelineDispatcher dispatcher = new PipelineDispatcher(story, mock(ScriptStep.class), audio, visuals, assembly,
            mock(FinalReview.class), mock(PublishStep.class), status);

    @Test
    void a_step_that_moves_its_story_straight_on_gets_the_next_step_queued() {
        doAnswer(i -> {
            // what the committed visuals → assembling change does
            dispatcher.dispatch(7, StoryFormat.FABLE, StoryStatus.ASSEMBLING);
            return null;
        }).when(visuals).run(7);

        dispatcher.dispatch(7, StoryFormat.FABLE, StoryStatus.VISUALS_IN_PROGRESS);

        verify(assembly, timeout(5000)).run(7);
    }

    @Test
    void the_same_step_is_not_queued_twice_while_it_runs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(i -> release.await(5, TimeUnit.SECONDS)).when(visuals).run(7);

        dispatcher.dispatch(7, StoryFormat.FABLE, StoryStatus.VISUALS_IN_PROGRESS);
        dispatcher.dispatch(7, StoryFormat.FABLE, StoryStatus.VISUALS_IN_PROGRESS);
        release.countDown();

        verify(visuals, timeout(5000).times(1)).run(7);
        Thread.sleep(200);
        verify(visuals, times(1)).run(7);
    }

    @Test
    void a_fable_runs_its_step() {
        dispatcher.dispatch(7, StoryFormat.FABLE, StoryStatus.AUDIO_IN_PROGRESS);

        verify(audio, timeout(5000)).run(7);
    }

    @Test
    void a_short_is_flagged_not_run_through_a_fable_step() {
        // Pipeline C scenario 11 / Pipeline D scenario 18: each format runs its own steps. Theirs are not built yet.
        dispatcher.dispatch(8, StoryFormat.MOTION_PICTURE, StoryStatus.STORY_IN_PROGRESS);
        dispatcher.dispatch(9, StoryFormat.STORY_SHORT, StoryStatus.AUDIO_IN_PROGRESS);

        verify(status, timeout(5000)).flag(eq(8L), eq(StoryStatus.STORY_IN_PROGRESS),
                contains("motion_picture step for story_in_progress is not built"), anyString());
        verify(status, timeout(5000)).flag(eq(9L), eq(StoryStatus.AUDIO_IN_PROGRESS),
                contains("story_short step"), anyString());
        verifyNoInteractions(story, audio);
    }
}

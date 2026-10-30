package com.storyforge.pipeline;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.storyforge.stage1.ScriptStep;
import com.storyforge.stage1.StoryStep;
import com.storyforge.stage2.AudioStep;
import com.storyforge.stage3.VisualsStep;
import com.storyforge.stage4.AssemblyStep;
import com.storyforge.stage4.FinalReview;
import com.storyforge.stage5.PublishStep;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PipelineDispatcherTest {

    VisualsStep visuals = mock(VisualsStep.class);
    AssemblyStep assembly = mock(AssemblyStep.class);
    PipelineDispatcher dispatcher = new PipelineDispatcher(mock(StoryStep.class), mock(ScriptStep.class),
            mock(AudioStep.class), visuals, assembly, mock(FinalReview.class), mock(PublishStep.class),
            mock(StoryStatusService.class));

    @Test
    void a_step_that_moves_its_story_straight_on_gets_the_next_step_queued() {
        doAnswer(i -> {
            dispatcher.dispatch(7, StoryStatus.ASSEMBLING); // what the committed visuals → assembling change does
            return null;
        }).when(visuals).run(7);

        dispatcher.dispatch(7, StoryStatus.VISUALS_IN_PROGRESS);

        verify(assembly, timeout(5000)).run(7);
    }

    @Test
    void the_same_step_is_not_queued_twice_while_it_runs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(i -> release.await(5, TimeUnit.SECONDS)).when(visuals).run(7);

        dispatcher.dispatch(7, StoryStatus.VISUALS_IN_PROGRESS);
        dispatcher.dispatch(7, StoryStatus.VISUALS_IN_PROGRESS);
        release.countDown();

        verify(visuals, timeout(5000).times(1)).run(7);
        Thread.sleep(200);
        verify(visuals, times(1)).run(7);
    }
}

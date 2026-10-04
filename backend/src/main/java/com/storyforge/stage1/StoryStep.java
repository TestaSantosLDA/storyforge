package com.storyforge.stage1;

import com.storyforge.character.CharacterFile;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.story.ArchiveReason;
import com.storyforge.story.StatusConflictException;
import com.storyforge.story.Story;
import com.storyforge.story.StoryCounter;
import com.storyforge.story.StoryCounters;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.story.StoryStatusService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Step 1A: story outline + cast. Generate, check (code checks, then Claude's judgement), and either hand to Gate A,
 * regenerate with the failed checks as feedback, archive when the QA budget is used up, or flag on an outage.
 */
@Component
public class StoryStep {

    static final String STAGE = "stage1";
    static final String TRIGGER = "system:stage1-story";
    private static final Logger log = LoggerFactory.getLogger(StoryStep.class);

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final StoryCounters counters;
    private final Drafts drafts;
    private final Cast cast;
    private final AiCalls ai;
    private final Flags flags;

    StoryStep(StoryRepository stories, StoryStatusService status, StoryCounters counters, Drafts drafts, Cast cast,
            AiCalls ai, Flags flags) {
        this.stories = stories;
        this.status = status;
        this.counters = counters;
        this.drafts = drafts;
        this.cast = cast;
        this.ai = ai;
        this.flags = flags;
    }

    public void run(long storyId) {
        String feedback = reviewerNotes(storyId);
        try {
            while (true) {
                Story story = stories.findWithTopic(storyId).orElseThrow();
                if (story.getStatus() != StoryStatus.STORY_IN_PROGRESS) {
                    return; // killed, or moved on elsewhere
                }
                long topicId = story.getTopic().getId();
                List<CharacterFile> topicCast = cast.topic(topicId);
                Map<String, String> vars = new LinkedHashMap<>();
                vars.put("topic_name", story.getTopic().getName());
                vars.put("topic_description", story.getTopicSnapshot());
                vars.put("concept", story.getConcept());
                vars.put("moral", story.getMoral());
                vars.put("target_length_min", String.valueOf(story.getTargetLengthMin()));
                vars.put("notes", story.getNotes() == null ? "(none)" : story.getNotes());
                vars.put("existing_characters", Cast.describe(topicCast));
                vars.put("roster", Cast.describe(cast.roster()));
                vars.put("available_voices", String.join(", ", cast.freeVoices(topicId)));
                vars.put("feedback", feedback.isBlank() ? "" : "\n## Fix these problems from the last attempt\n" + feedback);

                AiCalls.Outcome gen = ai.call(storyId, STAGE, "story", "story-v1", "story-v1", vars);
                List<QaCheck> checks = new ArrayList<>();
                StoryContent content = null;
                if (!gen.ok()) {
                    checks.add(QaCheck.code("format", false, "answer was not valid structured output: " + gen.badOutput()));
                } else {
                    content = Json.read(gen.json(), StoryContent.class);
                    checks.addAll(cast.check(content, topicId));
                    Map<String, String> qaVars = Map.of(
                            "concept", story.getConcept(), "moral", story.getMoral(),
                            "existing_characters", Cast.describe(topicCast),
                            "roster", Cast.describe(cast.roster()), "story_json", gen.json());
                    checks.addAll(ai.judge(storyId, STAGE, "story_qa", "story-qa-v1", qaVars));
                }

                if (QaCheck.allPass(checks)) {
                    drafts.add(storyId, Drafts.Kind.STORY, gen.json(), Json.write(checks), "story-v1");
                    status.transition(storyId, StoryStatus.STORY_IN_PROGRESS, StoryStatus.AWAITING_STORY_APPROVAL,
                            TRIGGER, "story QA passed: " + content.title());
                    return;
                }
                StoryCounters.Count count = counters.increment(storyId, StoryCounter.STORY_QA_RETRIES);
                if (count.reachedLimit()) {
                    status.archive(storyId, StoryStatus.STORY_IN_PROGRESS, ArchiveReason.STORY_QA_RETRIES_EXHAUSTED,
                            TRIGGER, QaCheck.feedback(checks));
                    return;
                }
                feedback = QaCheck.feedback(checks);
                log.info("story {} QA failed ({}/{}); regenerating", storyId, count.value(), count.limit());
            }
        } catch (LlmUnavailableException e) {
            flags.unavailable(storyId, StoryStatus.STORY_IN_PROGRESS, e, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("story {} changed while step 1A ran: {}", storyId, e.getMessage());
        }
    }

    private String reviewerNotes(long storyId) {
        return drafts.latest(storyId, Drafts.Kind.STORY)
                .filter(d -> "rejected".equals(d.reviewState()) && d.reviewerNotes() != null)
                .map(d -> "- Reviewer notes on version " + d.version() + ": " + d.reviewerNotes())
                .orElse("");
    }
}

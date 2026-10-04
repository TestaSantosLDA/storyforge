package com.storyforge.stage1;

import com.storyforge.character.CharacterFile;
import com.storyforge.config.StoryforgeProperties;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Step 1B: speaker-tagged script and scene breakdown from the approved story and locked cast. */
@Component
public class ScriptStep {

    static final String TRIGGER = "system:stage1-script";
    private static final Logger log = LoggerFactory.getLogger(ScriptStep.class);

    private final StoryRepository stories;
    private final StoryStatusService status;
    private final StoryCounters counters;
    private final Drafts drafts;
    private final Cast cast;
    private final AiCalls ai;
    private final Flags flags;
    private final StoryforgeProperties.Script limits;

    ScriptStep(StoryRepository stories, StoryStatusService status, StoryCounters counters, Drafts drafts, Cast cast,
            AiCalls ai, Flags flags, StoryforgeProperties props) {
        this.stories = stories;
        this.status = status;
        this.counters = counters;
        this.drafts = drafts;
        this.cast = cast;
        this.ai = ai;
        this.flags = flags;
        this.limits = props.script();
    }

    public void run(long storyId) {
        String feedback = reviewerNotes(storyId);
        try {
            while (true) {
                Story story = stories.findWithTopic(storyId).orElseThrow();
                if (story.getStatus() != StoryStatus.SCRIPT_IN_PROGRESS) {
                    return;
                }
                Drafts.Draft approvedStory = drafts.approved(storyId, Drafts.Kind.STORY)
                        .orElseThrow(() -> new IllegalStateException("no approved story for " + storyId));
                StoryContent storyContent = Json.read(approvedStory.content(), StoryContent.class);
                Map<String, String> castLines = lockedCast(storyContent, story.getTopic().getId());
                int[] words = wordRange(story.getTargetLengthMin());

                Map<String, String> vars = new LinkedHashMap<>();
                vars.put("story_json", approvedStory.content());
                vars.put("cast", castLines.entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining("\n")));
                vars.put("target_words", String.valueOf(story.getTargetLengthMin() * limits.wordsPerMinute()));
                vars.put("min_words", String.valueOf(words[0]));
                vars.put("max_words", String.valueOf(words[1]));
                vars.put("target_length_min", String.valueOf(story.getTargetLengthMin()));
                vars.put("feedback", feedback.isBlank() ? "" : "\n## Fix these problems from the last attempt\n" + feedback);

                AiCalls.Outcome gen = ai.call(storyId, StoryStep.STAGE, "script", "script-v1", "script-v1", vars);
                List<QaCheck> checks = new ArrayList<>();
                if (!gen.ok()) {
                    checks.add(QaCheck.code("format", false, "answer was not valid structured output: " + gen.badOutput()));
                } else {
                    ScriptContent script = Json.read(gen.json(), ScriptContent.class);
                    checks.addAll(check(script, castLines.keySet(), words));
                    checks.addAll(ai.judge(storyId, StoryStep.STAGE, "script_qa", "script-qa-v1",
                            Map.of("story_json", approvedStory.content(), "script_json", gen.json())));
                }

                if (QaCheck.allPass(checks)) {
                    drafts.add(storyId, Drafts.Kind.SCRIPT, gen.json(), Json.write(checks), "script-v1");
                    status.transition(storyId, StoryStatus.SCRIPT_IN_PROGRESS, StoryStatus.AWAITING_SCRIPT_APPROVAL,
                            TRIGGER, "script QA passed");
                    return;
                }
                StoryCounters.Count count = counters.increment(storyId, StoryCounter.SCRIPT_QA_RETRIES);
                if (count.reachedLimit()) {
                    status.archive(storyId, StoryStatus.SCRIPT_IN_PROGRESS, ArchiveReason.SCRIPT_QA_RETRIES_EXHAUSTED,
                            TRIGGER, QaCheck.feedback(checks));
                    return;
                }
                feedback = QaCheck.feedback(checks);
                log.info("story {} script QA failed ({}/{}); regenerating", storyId, count.value(), count.limit());
            }
        } catch (LlmUnavailableException e) {
            flags.unavailable(storyId, StoryStatus.SCRIPT_IN_PROGRESS, e, TRIGGER);
        } catch (StatusConflictException e) {
            log.info("story {} changed while step 1B ran: {}", storyId, e.getMessage());
        }
    }

    /** id → description of everyone allowed to speak besides the narrator. */
    Map<String, String> lockedCast(StoryContent story, long topicId) {
        Map<String, CharacterFile> byId = new HashMap<>();
        cast.topic(topicId).forEach(c -> byId.put(c.id(), c));
        Map<String, String> out = new LinkedHashMap<>();
        for (StoryContent.ReusedCharacter r : story.reusedCharacters()) {
            CharacterFile c = byId.get(r.id());
            out.put(r.id(), (c == null ? r.id() : c.name()) + ", " + r.roleInStory());
        }
        for (StoryContent.NewCharacter n : story.newCharacters()) {
            out.put(n.id(), n.name() + ", " + n.reason());
        }
        return out;
    }

    int[] wordRange(int targetMin) {
        int target = targetMin * limits.wordsPerMinute();
        int min = Math.max(limits.minWords(), (int) Math.round(target * (1 - limits.tolerance())));
        int max = Math.min(limits.maxWords(), (int) Math.round(target * (1 + limits.tolerance())));
        return new int[] {Math.min(min, max), max};
    }

    /** Deterministic script checks from the Stage 1 QA table. */
    static List<QaCheck> check(ScriptContent script, Set<String> castIds, int[] words) {
        Set<String> allowed = new LinkedHashSet<>(castIds);
        allowed.add(ScriptContent.NARRATOR);

        Set<String> strangers = script.lines().stream().map(ScriptContent.Line::speaker)
                .filter(s -> s != null && !allowed.contains(s)).collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> tagProblems = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ScriptContent.Line l : script.lines()) {
            if (l.speaker() == null || l.speaker().isBlank() || l.speaker().contains(",")) {
                tagProblems.add(l.id() + " needs exactly one speaker");
            }
            if (l.text() == null || l.text().isBlank()) {
                tagProblems.add(l.id() + " is empty");
            }
            if (!ids.add(l.id())) {
                tagProblems.add("line id " + l.id() + " is used twice");
            }
        }

        List<String> mapping = new ArrayList<>();
        List<String> inSceneOrder = script.scenes().stream().flatMap(s -> s.lineIds().stream()).toList();
        List<String> lineOrder = script.lines().stream().map(ScriptContent.Line::id).toList();
        if (!inSceneOrder.equals(lineOrder)) {
            Set<String> covered = new LinkedHashSet<>(inSceneOrder);
            lineOrder.stream().filter(id -> !covered.contains(id)).forEach(id -> mapping.add(id + " is in no scene"));
            inSceneOrder.stream().filter(id -> !ids.contains(id)).forEach(id -> mapping.add("scenes mention unknown line " + id));
            if (inSceneOrder.size() != new LinkedHashSet<>(inSceneOrder).size()) {
                mapping.add("a line appears in more than one scene");
            }
            if (mapping.isEmpty()) {
                mapping.add("scenes must cover the lines in script order");
            }
        }

        int count = script.wordCount();
        return List.of(
                QaCheck.code("length", count >= words[0] && count <= words[1],
                        count + " words; needs " + words[0] + " to " + words[1]),
                QaCheck.code("cast_lock", strangers.isEmpty(), "speakers not in the approved cast: " + strangers
                        + "; only " + allowed + " may speak"),
                QaCheck.code("speaker_tags", tagProblems.isEmpty(), String.join("; ", tagProblems)),
                QaCheck.code("scene_mapping", mapping.isEmpty(), String.join("; ", mapping)));
    }

    private String reviewerNotes(long storyId) {
        return drafts.latest(storyId, Drafts.Kind.SCRIPT)
                .filter(d -> "rejected".equals(d.reviewState()) && d.reviewerNotes() != null)
                .map(d -> "- Reviewer notes on version " + d.version() + ": " + d.reviewerNotes())
                .orElse("");
    }
}

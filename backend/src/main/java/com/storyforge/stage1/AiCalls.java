package com.storyforge.stage1;

import com.storyforge.llm.LlmBadOutputException;
import com.storyforge.llm.LlmCaller;
import com.storyforge.llm.LlmRequest;
import com.storyforge.llm.LlmResult;
import com.storyforge.llm.LlmUnavailableException;
import com.storyforge.log.Attempt;
import com.storyforge.log.AttemptLog;
import com.storyforge.log.AttemptRepository;
import com.storyforge.prompt.PromptLibrary;
import com.storyforge.storage.InputHash;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Runs one AI call and logs it as an attempt, whatever happens (CLAUDE.md rule 8). */
@Component
class AiCalls {

    static final String SYSTEM_PROMPT = "system-v1";

    private final LlmCaller llm;
    private final AttemptLog attemptLog;
    private final AttemptRepository attempts;
    private final PromptLibrary prompts;

    AiCalls(LlmCaller llm, AttemptLog attemptLog, AttemptRepository attempts, PromptLibrary prompts) {
        this.llm = llm;
        this.attemptLog = attemptLog;
        this.attempts = attempts;
        this.prompts = prompts;
    }

    /** Result of a call: the structured JSON, or null with {@code badOutput} set. */
    record Outcome(String json, String badOutput) {
        boolean ok() {
            return json != null;
        }
    }

    Outcome call(long storyId, String stage, String step, String promptVersion, String schemaVersion,
            Map<String, String> vars) {
        PromptLibrary.Prompt prompt = prompts.render(promptVersion, vars);
        String system = prompts.text(SYSTEM_PROMPT);
        String schema = prompts.schema(schemaVersion);
        int attemptNo = (int) attempts.countByStoryIdAndStep(storyId, step) + 1;
        Attempt attempt = new Attempt(storyId, stage, step, attemptNo)
                .versions(promptVersion + "+" + SYSTEM_PROMPT + "+" + schemaVersion, null, llm.engineName())
                .inputs(InputHash.of(system, prompt.text(), schema), Json.write(vars));
        try {
            LlmResult r = llm.call(new LlmRequest(prompt.version(), system, prompt.text(), schema));
            attempt.versions(promptVersion + "+" + SYSTEM_PROMPT + "+" + schemaVersion, r.model(), llm.engineName());
            attemptLog.record(attempt.result("ok", r.raw(), null, null, r.billedUsd(), null));
            return new Outcome(r.json(), null);
        } catch (LlmBadOutputException e) {
            attemptLog.record(attempt.result("bad_output", e.raw(), null, null, null, e.getMessage()));
            return new Outcome(null, e.getMessage());
        } catch (LlmUnavailableException e) {
            attemptLog.record(attempt.result("unavailable", null, null, null, null, e.getMessage()));
            throw e;
        }
    }

    record Judged(List<Check> checks) {
        record Check(String name, boolean pass, String feedback) {
        }
    }

    /** Claude-judged QA: returns the checks it reports. */
    List<QaCheck> judge(long storyId, String stage, String step, String promptVersion, Map<String, String> vars) {
        Outcome o = call(storyId, stage, step, promptVersion, "qa-v1", vars);
        if (!o.ok()) {
            // The judge itself failed to answer in shape: not the story's fault.
            throw new LlmUnavailableException("QA answer did not match its schema: " + o.badOutput());
        }
        return Json.read(o.json(), Judged.class).checks().stream()
                .map(c -> new QaCheck(c.name(), c.pass(), c.pass() ? "" : c.feedback(), "claude"))
                .toList();
    }
}

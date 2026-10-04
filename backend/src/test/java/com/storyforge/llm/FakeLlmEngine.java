package com.storyforge.llm;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scripted stand-in for Claude in tests. Answers are queued per prompt family ("story", "story-qa", "script",
 * "script-qa"); when a queue is empty the family's default answer is used. Every request is recorded.
 */
public class FakeLlmEngine implements LlmEngine {

    public static final String UNAVAILABLE = "<<unavailable>>";
    public static final String BAD_OUTPUT = "<<bad-output>>";
    public static final String USAGE_LIMIT = "<<usage-limit>>";

    private final Map<String, Deque<String>> queued = new HashMap<>();
    private final Map<String, Function<LlmRequest, String>> defaults = new HashMap<>();
    public final List<LlmRequest> requests = new ArrayList<>();

    public synchronized void reset() {
        queued.clear();
        defaults.clear();
        requests.clear();
    }

    public synchronized FakeLlmEngine then(String family, String answer) {
        queued.computeIfAbsent(family, k -> new ArrayDeque<>()).add(answer);
        return this;
    }

    public synchronized FakeLlmEngine byDefault(String family, String answer) {
        defaults.put(family, r -> answer);
        return this;
    }

    public synchronized List<LlmRequest> requests(String family) {
        return requests.stream().filter(r -> family(r).equals(family)).toList();
    }

    @Override
    public synchronized LlmResult complete(LlmRequest request) {
        requests.add(request);
        String family = family(request);
        Deque<String> q = queued.get(family);
        String answer = q != null && !q.isEmpty() ? q.poll()
                : defaults.getOrDefault(family, r -> { throw new IllegalStateException("no fake answer for " + family); })
                        .apply(request);
        if (UNAVAILABLE.equals(answer)) {
            throw new LlmUnavailableException("fake outage");
        }
        if (USAGE_LIMIT.equals(answer)) {
            throw new UsageLimitException("fake usage limit", null);
        }
        if (BAD_OUTPUT.equals(answer)) {
            throw new LlmBadOutputException("fake bad output", "garbage");
        }
        return new LlmResult(answer, "{\"fake\":true}", "fake-model", BigDecimal.ZERO, new BigDecimal("0.01"), 1);
    }

    @Override
    public String name() {
        return "fake";
    }

    /** "story-v1" → "story", "story-qa-v1" → "story-qa". */
    static String family(LlmRequest r) {
        return r.promptVersion().replaceAll("-v\\d+$", "");
    }
}

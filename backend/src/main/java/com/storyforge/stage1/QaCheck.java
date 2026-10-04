package com.storyforge.stage1;

import java.util.List;
import java.util.stream.Collectors;

/** One QA check result. {@code source} is "code" for deterministic checks, "claude" for judged ones. */
public record QaCheck(String name, boolean pass, String feedback, String source) {

    public static QaCheck code(String name, boolean pass, String feedback) {
        return new QaCheck(name, pass, pass ? "" : feedback, "code");
    }

    public static boolean allPass(List<QaCheck> checks) {
        return checks.stream().allMatch(QaCheck::pass);
    }

    /** The failed checks as feedback for the next attempt. */
    public static String feedback(List<QaCheck> checks) {
        return checks.stream().filter(c -> !c.pass())
                .map(c -> "- " + c.name() + ": " + c.feedback())
                .collect(Collectors.joining("\n"));
    }
}

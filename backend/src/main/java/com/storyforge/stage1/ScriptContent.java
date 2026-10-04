package com.storyforge.stage1;

import java.util.List;

/** Step 1B output, shaped by {@code prompts/schemas/script-v1.json}. Speaker is "narrator" or a character id. */
public record ScriptContent(List<Line> lines, List<Scene> scenes) {

    public static final String NARRATOR = "narrator";

    public record Line(String id, String speaker, String text) {
    }

    public record Scene(String id, String visualDescription, String mood, List<String> lineIds) {
    }

    public int wordCount() {
        return lines.stream().mapToInt(l -> l.text().isBlank() ? 0 : l.text().strip().split("\\s+").length).sum();
    }
}

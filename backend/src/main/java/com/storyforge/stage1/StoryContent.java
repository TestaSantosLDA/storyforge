package com.storyforge.stage1;

import java.util.List;

/** Step 1A output, shaped by {@code prompts/schemas/story-v1.json} (snake_case in JSON). */
public record StoryContent(
        String title,
        Outline outline,
        List<String> beats,
        String moral,
        List<ReusedCharacter> reusedCharacters,
        List<NewCharacter> newCharacters) {

    public record Outline(String setup, String problem, String journey, String resolution, String moralMoment) {
    }

    public record ReusedCharacter(String id, String roleInStory) {
    }

    public record NewCharacter(String id, String name, String kind, String personality, String visualDescription,
            String proposedVoice, String reason) {
    }
}

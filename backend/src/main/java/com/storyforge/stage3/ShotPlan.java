package com.storyforge.stage3;

import java.util.List;

/** Step 3A output, shaped by {@code prompts/schemas/shots-v1.json}; start/end are added from the timing manifest. */
public record ShotPlan(List<Shot> shots) {

    public record Shot(String id, String sceneId, List<String> lineIds, List<String> characters, List<Action> actions,
            String setting, String framing, String camera, Double start, Double end) {

        public double seconds() {
            return end - start;
        }

        public Shot timed(double start, double end) {
            return new Shot(id, sceneId, lineIds, characters, actions, setting, framing, camera, start, end);
        }
    }

    public record Action(String character, String action) {
    }
}

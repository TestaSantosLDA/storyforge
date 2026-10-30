package com.storyforge.web;

import com.storyforge.stage1.Drafts;
import com.storyforge.stage3.ShotPlan;
import com.storyforge.stage3.ShotStore;
import com.storyforge.story.StoryQueueService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Stage 3 needs-attention option: accept the best image for every shot that kept failing QA, then resume. */
@Controller
class VisualsController {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final ShotStore shots;
    private final Drafts drafts;
    private final StoryQueueService queue;

    VisualsController(ShotStore shots, Drafts drafts, StoryQueueService queue) {
        this.shots = shots;
        this.drafts = drafts;
        this.queue = queue;
    }

    @PostMapping("/stories/{id}/visuals/accept-best")
    String acceptBest(@PathVariable long id, RedirectAttributes flash) {
        ShotPlan plan = JSON.readValue(drafts.approved(id, Drafts.Kind.SHOTS).orElseThrow().content(), ShotPlan.class);
        int accepted = 0;
        for (ShotPlan.Shot s : plan.shots()) {
            if (shots.usable(id, s.id()).isEmpty()) {
                var best = shots.best(id, s.id());
                if (best.isPresent()) {
                    shots.accept(best.get().id());
                    accepted++;
                }
            }
        }
        queue.resume(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Accepted the best image for " + accepted + " shot(s). Animating now.");
        return "redirect:/stories/" + id;
    }
}

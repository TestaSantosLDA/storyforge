package com.storyforge.web;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.story.RunResult;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatusService;
import com.storyforge.topic.TopicService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class QueueController {

    private final StoryQueueService queue;
    private final StoryRepository stories;
    private final TopicService topics;
    private final StoryforgeProperties props;

    QueueController(StoryQueueService queue, StoryRepository stories, TopicService topics, StoryforgeProperties props) {
        this.queue = queue;
        this.stories = stories;
        this.topics = topics;
        this.props = props;
    }

    @GetMapping("/")
    String home() {
        return "redirect:/queue";
    }

    @GetMapping("/queue")
    String queue(Model model) {
        List<Story> queued = queue.queue();
        long inFlight = stories.countByStatusIn(StoryStatusService.inFlightStatuses());
        int cap = props.pipeline().maxConcurrentStories();
        model.addAttribute("stories", queued);
        model.addAttribute("inFlight", inFlight);
        model.addAttribute("cap", cap);
        model.addAttribute("canRun", !queued.isEmpty() && inFlight < cap);
        model.addAttribute("topics", topics.all());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", StoryForm.blank(null));
        }
        model.addAttribute("state", state(queued, inFlight));
        return "queue";
    }

    @PostMapping("/queue/run")
    String run(RedirectAttributes flash) {
        switch (queue.run(Actor.LOCAL)) {
            case RunResult.Started s -> flash.addFlashAttribute("notice", "Started: " + stories.findById(s.storyId())
                    .map(Story::getConcept).orElse("story " + s.storyId()));
            case RunResult.NothingToRun n -> flash.addFlashAttribute("error", "Nothing to run: the queue is empty.");
            case RunResult.AtCapacity c -> flash.addFlashAttribute("error",
                    "Already " + c.inFlight() + " in flight (limit " + c.cap() + ").");
        }
        return "redirect:/queue";
    }

    record ReorderRequest(long storyId, int position) {
    }

    /** Drag-to-reorder. Saved immediately; returns the stored order so the page can match it. */
    @PostMapping("/queue/reorder")
    @ResponseBody
    ResponseEntity<Map<String, Object>> reorder(@RequestBody ReorderRequest req) {
        queue.reorder(req.storyId(), req.position(), Actor.LOCAL);
        List<Story> queued = queue.queue();
        return ResponseEntity.ok(Map.of(
                "order", queued.stream().map(Story::getId).toList(),
                "state", state(queued, stories.countByStatusIn(StoryStatusService.inFlightStatuses()))));
    }

    /** Small fingerprint of the queue so another open tab can notice a change and reload (scenario 13). */
    @GetMapping("/queue/state")
    @ResponseBody
    Map<String, String> state() {
        return Map.of("state", state(queue.queue(), stories.countByStatusIn(StoryStatusService.inFlightStatuses())));
    }

    private static String state(List<Story> queued, long inFlight) {
        StringBuilder sb = new StringBuilder().append(inFlight).append(':');
        for (Story s : queued) {
            sb.append(s.getId()).append('.').append(s.getUpdatedAt() == null ? 0 : s.getUpdatedAt().toEpochMilli())
                    .append(',');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}

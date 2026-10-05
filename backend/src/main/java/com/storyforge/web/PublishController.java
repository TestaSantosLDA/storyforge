package com.storyforge.web;

import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage5.Publishing;
import com.storyforge.story.Story;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.validation.InvalidInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;

/** The publish screen (docs/pipeline-a/stage-5 "Publish package and checklist"). */
@Controller
class PublishController {

    record UsedTrack(long id, String title, String license, String licenseKey) {
    }

    private final StoryRepository stories;
    private final Publishing publishing;
    private final JdbcClient jdbc;

    PublishController(StoryRepository stories, Publishing publishing, JdbcClient jdbc) {
        this.stories = stories;
        this.publishing = publishing;
        this.jdbc = jdbc;
    }

    @GetMapping("/stories/{id}/publish")
    String show(@PathVariable long id, Model model) {
        Story story = stories.findWithTopic(id).orElseThrow(() -> new NotFoundException("story " + id));
        model.addAttribute("story", story);
        model.addAttribute("ready", story.getStatus() == StoryStatus.READY_TO_UPLOAD);
        model.addAttribute("pkg", publishing.latest(id).orElse(null));
        model.addAttribute("url", publishing.publishedUrl(id).orElse(null));
        var assembly = jdbc.sql("select video_key, srt_key, music::text as music from assembly where story_id = :s "
                        + "order by version desc limit 1")
                .param("s", id).query((rs, n) -> Map.of("video", rs.getString(1), "srt", rs.getString(2),
                        "music", rs.getString(3) == null ? "[]" : rs.getString(3))).optional().orElse(null);
        model.addAttribute("assembly", assembly);
        List<UsedTrack> tracks = new ArrayList<>();
        if (assembly != null) {
            for (JsonNode m : ShotPlanner.JSON.readTree(assembly.get("music"))) {
                long trackId = m.path("track_id").asLong();
                jdbc.sql("select id, title, license_type, license_key from music_track where id = :t").param("t", trackId)
                        .query((rs, n) -> new UsedTrack(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                        .optional().ifPresent(t -> {
                            if (tracks.stream().noneMatch(x -> x.id() == t.id())) {
                                tracks.add(t);
                            }
                        });
            }
        }
        model.addAttribute("tracks", tracks);
        return "publish";
    }

    @PostMapping("/stories/{id}/publish/edit")
    String edit(@PathVariable long id, @RequestParam String title, @RequestParam String description,
            @RequestParam String tags, RedirectAttributes flash) {
        return act(id, flash, "Saved.", () -> publishing.edit(id, title, description, tags));
    }

    @PostMapping("/stories/{id}/publish/regenerate")
    String regenerate(@PathVariable long id, @RequestParam String notes, RedirectAttributes flash) {
        return act(id, flash, "Rewriting the metadata with your notes.", () -> publishing.regenerate(id, notes, Actor.LOCAL));
    }

    @PostMapping("/stories/{id}/publish/published")
    String published(@PathVariable long id, @RequestParam String url, RedirectAttributes flash) {
        return act(id, flash, "Marked as published.", () -> publishing.markPublished(id, url, Actor.LOCAL));
    }

    @PostMapping("/stories/{id}/publish/music-claim")
    String musicClaim(@PathVariable long id, @RequestParam long trackId, @RequestParam String action,
            RedirectAttributes flash) {
        boolean dispute = "dispute".equals(action);
        return act(id, flash, dispute ? "Recorded. Dispute the claim in YouTube Studio with the license file."
                : "Replacing that track; the corrected video comes back to final review.",
                () -> publishing.musicClaimed(id, trackId, dispute, Actor.LOCAL));
    }

    @PostMapping("/stories/{id}/publish/visual-claim")
    String visualClaim(@PathVariable long id, @RequestParam String claim, RedirectAttributes flash) {
        return act(id, flash, "Working out what to redo from the claim.", () -> publishing.visualClaim(id, claim, Actor.LOCAL));
    }

    @PostMapping("/stories/{id}/publish/kill")
    String kill(@PathVariable long id, RedirectAttributes flash) {
        return act(id, flash, "Story archived; it won't be published.", () -> publishing.kill(id, Actor.LOCAL));
    }

    private String act(long id, RedirectAttributes flash, String ok, Runnable action) {
        try {
            action.run();
            flash.addFlashAttribute("notice", ok);
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", String.join(" ", e.fieldErrors().values()));
        }
        return "redirect:/stories/" + id + "/publish";
    }
}

package com.storyforge.web;

import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage2.AudioLines;
import com.storyforge.stage3.ShotPlan;
import com.storyforge.stage3.ShotPlanner;
import com.storyforge.stage3.ShotStore;
import com.storyforge.stage4.FinalReview;
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

/** The final review screen (docs/pipeline-a/stage-4 "Final review gate"). */
@Controller
class ReviewController {

    record ShotRow(String id, String time, String thumbnail) {
    }

    record MusicRow(String title, String license, String scenes, String time) {
    }

    record Assembly(int version, String videoKey, String srtKey, double durationSec, double introSec, String qa,
            String music) {
    }

    private final StoryRepository stories;
    private final Drafts drafts;
    private final AudioLines audio;
    private final ShotStore shots;
    private final FinalReview review;
    private final JdbcClient jdbc;

    ReviewController(StoryRepository stories, Drafts drafts, AudioLines audio, ShotStore shots, FinalReview review,
            JdbcClient jdbc) {
        this.stories = stories;
        this.drafts = drafts;
        this.audio = audio;
        this.shots = shots;
        this.review = review;
        this.jdbc = jdbc;
    }

    @GetMapping("/stories/{id}/review")
    String show(@PathVariable long id, Model model) {
        Story story = stories.findWithTopic(id).orElseThrow(() -> new NotFoundException("story " + id));
        model.addAttribute("story", story);
        model.addAttribute("waiting", story.getStatus() == StoryStatus.AWAITING_FINAL_REVIEW);
        var assembly = jdbc.sql("select * from assembly where story_id = :s order by version desc limit 1")
                .param("s", id).query((rs, n) -> new Assembly(rs.getInt("version"), rs.getString("video_key"),
                        rs.getString("srt_key"), rs.getBigDecimal("duration_sec").doubleValue(),
                        rs.getBigDecimal("intro_sec").doubleValue(), rs.getString("qa"), rs.getString("music")))
                .optional().orElse(null);
        model.addAttribute("assembly", assembly);
        if (assembly != null) {
            List<ShotRow> shotRows = new ArrayList<>();
            var track = audio.track(id).orElseThrow();
            ShotPlan plan = ShotPlanner.time(ShotPlanner.JSON.readValue(
                    drafts.approved(id, Drafts.Kind.SHOTS).orElseThrow().content(), ShotPlan.class),
                    ShotPlanner.timing(track.manifest()), track.durationSec());
            for (ShotPlan.Shot s : plan.shots()) {
                String thumb = shots.usable(id, s.id()).map(ShotStore.Image::assetKey).orElse(null);
                shotRows.add(new ShotRow(s.id(), clock(assembly.introSec() + s.start()), thumb));
            }
            model.addAttribute("shotRows", shotRows);
            List<MusicRow> musicRows = new ArrayList<>();
            for (JsonNode m : ShotPlanner.JSON.readTree(assembly.music() == null ? "[]" : assembly.music())) {
                musicRows.add(new MusicRow(m.path("title").asString(), m.path("license").asString(),
                        m.path("scenes").toString(), clock(m.path("start").asDouble()) + "–" + clock(m.path("end").asDouble())));
            }
            model.addAttribute("musicRows", musicRows);
            model.addAttribute("checks", List.of(ShotPlanner.JSON.readValue(assembly.qa(), Map[].class)));
        }
        drafts.approved(id, Drafts.Kind.SCRIPT).ifPresent(d ->
                model.addAttribute("script", ShotPlanner.JSON.readValue(d.content(), ScriptContent.class)));
        model.addAttribute("rejection", review.latestRejection(id).orElse(null));
        return "review";
    }

    @PostMapping("/stories/{id}/review/approve")
    String approve(@PathVariable long id, RedirectAttributes flash) {
        review.approve(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Approved. Ready for publishing.");
        return "redirect:/stories/" + id;
    }

    @PostMapping("/stories/{id}/review/reject")
    String reject(@PathVariable long id, @RequestParam String reason, RedirectAttributes flash) {
        try {
            review.reject(id, reason, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Rejected. Working out what to redo from your reason.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", e.fieldErrors().get("reason"));
            return "redirect:/stories/" + id + "/review";
        }
        return "redirect:/stories/" + id;
    }

    @PostMapping("/stories/{id}/review/kill")
    String kill(@PathVariable long id, RedirectAttributes flash) {
        review.kill(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Story killed and archived.");
        return "redirect:/stories/" + id;
    }

    static String clock(double seconds) {
        long s = Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}

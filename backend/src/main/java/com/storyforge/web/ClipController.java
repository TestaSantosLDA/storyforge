package com.storyforge.web;

import com.storyforge.clips.ClipJobs;
import com.storyforge.clips.ClipReview;
import com.storyforge.clips.ClipText;
import com.storyforge.storage.AssetStorage;
import com.storyforge.validation.InvalidInputException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Clips from a video" page and each job's review screen (docs/pipeline-b/clips-from-any-video.md). */
@Controller
class ClipController {

    private static final Set<String> VIDEO_TYPES = Set.of(".mp4", ".mov", ".mkv", ".webm");

    record JobRow(ClipJobs.Job job, String title, int clips) {
    }

    record ClipView(ClipJobs.Clip clip, String spoken, List<ClipJobs.Variant> variants) {
    }

    private final ClipJobs jobs;
    private final ClipReview review;
    private final AssetStorage assets;

    ClipController(ClipJobs jobs, ClipReview review, AssetStorage assets) {
        this.jobs = jobs;
        this.review = review;
        this.assets = assets;
    }

    @GetMapping("/clips")
    String list(Model model) {
        model.addAttribute("rows", jobs.all().stream().map(j -> new JobRow(j, title(j), jobs.clips(j.id()).size())).toList());
        model.addAttribute("channels", jobs.channels());
        return "clips";
    }

    @PostMapping("/clips")
    String submit(@RequestParam(required = false) MultipartFile file, @RequestParam(required = false) String url,
            @RequestParam(required = false) String sourceText, @RequestParam(defaultValue = "false") boolean ownVideo,
            @RequestParam(required = false) String channel, @RequestParam(required = false) Integer clipCount,
            @RequestParam(required = false) String notes, @RequestParam(defaultValue = "false") boolean spoilersAllowed,
            @RequestParam(required = false) String reframeMode, RedirectAttributes flash) throws IOException {
        String key = null;
        try {
            key = store(file);
            long id = jobs.submit(new ClipJobs.Form(url, key, key == null ? null : file.getOriginalFilename(), sourceText,
                    ownVideo, channel, clipCount, notes, spoilersAllowed, reframeMode), Actor.LOCAL);
            return "redirect:/clips/" + id;
        } catch (InvalidInputException e) {
            if (key != null) {
                assets.delete(key);
            }
            flash.addFlashAttribute("error", String.join(" ", e.fieldErrors().values()));
            return "redirect:/clips";
        }
    }

    /** Written to disk as it arrives; a full-length video never sits in memory. */
    private String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        if (!VIDEO_TYPES.contains(ext)) {
            throw InvalidInputException.of("video", "Upload an mp4, mov, mkv or webm file.");
        }
        String key = "clips/incoming/" + UUID.randomUUID() + ext;
        try (InputStream in = file.getInputStream()) {
            assets.put(key, in);
        }
        return key;
    }

    @GetMapping("/clips/{id}")
    String job(@PathVariable long id, Model model) {
        ClipJobs.Job job = jobs.find(id).orElseThrow(() -> new NotFoundException("clip job " + id));
        ClipJobs.Source source = jobs.source(job.sourceVideoId()).orElse(null);
        List<ClipView> views = new ArrayList<>();
        for (ClipJobs.Clip c : jobs.clips(id)) {
            views.add(new ClipView(c, source == null || source.lines() == null ? "" : ClipText.spoken(source, c), jobs.variants(c.id())));
        }
        model.addAttribute("job", job);
        model.addAttribute("title", title(job));
        model.addAttribute("source", source);
        model.addAttribute("clips", views);
        return "clip-job";
    }

    private String title(ClipJobs.Job j) {
        return jobs.source(j.sourceVideoId()).map(ClipJobs.Source::title)
                .orElse(j.uploadName() != null ? j.uploadName() : j.url());
    }

    @PostMapping("/clips/{id}/resume")
    String resume(@PathVariable long id, RedirectAttributes flash) {
        return act(id, flash, "Going again.", () -> jobs.resume(id, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/file")
    String newFile(@PathVariable long id, @RequestParam MultipartFile file, RedirectAttributes flash) throws IOException {
        try {
            String key = store(file);
            if (key == null) {
                throw InvalidInputException.of("video", "Choose the file.");
            }
            jobs.replaceFile(id, key, file.getOriginalFilename(), Actor.LOCAL);
            flash.addFlashAttribute("notice", "Using the new file.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", String.join(" ", e.fieldErrors().values()));
        }
        return "redirect:/clips/" + id;
    }

    @PostMapping("/clips/{id}/slice")
    String slice(@PathVariable long id, @RequestParam String startLine, @RequestParam String endLine,
            @RequestParam String hook, RedirectAttributes flash) {
        return act(id, flash, "Rendering that slice.", () -> review.manualSlice(id, startLine, endLine, hook, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/kill")
    String killJob(@PathVariable long id, RedirectAttributes flash) {
        return act(id, flash, "Job stopped.", () -> review.killJob(id, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/clip/{clipId}/approve")
    String approve(@PathVariable long id, @PathVariable long clipId, RedirectAttributes flash) {
        return act(id, flash, "Approved. Post it, then record the links.", () -> review.approve(clipId, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/clip/{clipId}/reject")
    String reject(@PathVariable long id, @PathVariable long clipId, @RequestParam String reason,
            @RequestParam(required = false) String framing, @RequestParam(required = false) Double offset,
            RedirectAttributes flash) {
        return act(id, flash, "Rejected.", () -> review.reject(clipId, reason, framing, offset, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/clip/{clipId}/kill")
    String kill(@PathVariable long id, @PathVariable long clipId, RedirectAttributes flash) {
        return act(id, flash, "Clip killed.", () -> review.kill(clipId, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/clip/{clipId}/skip")
    String skip(@PathVariable long id, @PathVariable long clipId, @RequestParam String platform, RedirectAttributes flash) {
        return act(id, flash, "Skipped.", () -> review.skip(clipId, platform, Actor.LOCAL));
    }

    @PostMapping("/clips/{id}/clip/{clipId}/posted")
    String posted(@PathVariable long id, @PathVariable long clipId, @RequestParam String platform,
            @RequestParam String url, RedirectAttributes flash) {
        return act(id, flash, "Recorded.", () -> review.markPosted(clipId, platform, url, Actor.LOCAL));
    }

    private String act(long id, RedirectAttributes flash, String ok, Runnable action) {
        try {
            action.run();
            flash.addFlashAttribute("notice", ok);
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", String.join(" ", e.fieldErrors().values()));
        }
        return "redirect:/clips/" + id;
    }
}

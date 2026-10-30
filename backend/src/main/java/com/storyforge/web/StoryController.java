package com.storyforge.web;

import com.storyforge.log.StatusChangeRepository;
import com.storyforge.story.Story;
import com.storyforge.story.StoryForm;
import com.storyforge.story.StoryQueueService;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class StoryController {

    private final StoryQueueService queue;
    private final StoryRepository stories;
    private final StatusChangeRepository changes;
    private final TopicService topics;
    private final com.storyforge.stage2.AudioLines audio;
    private final com.storyforge.stage3.ShotStore shotStore;

    StoryController(StoryQueueService queue, StoryRepository stories, StatusChangeRepository changes,
            TopicService topics, com.storyforge.stage2.AudioLines audio,
            com.storyforge.stage3.ShotStore shotStore) {
        this.queue = queue;
        this.stories = stories;
        this.changes = changes;
        this.topics = topics;
        this.audio = audio;
        this.shotStore = shotStore;
    }

    @GetMapping("/stories/new")
    String newStory(@RequestParam(required = false) Long topicId, Model model) {
        model.addAttribute("form", StoryForm.blank(topicId));
        model.addAttribute("topics", topics.all());
        model.addAttribute("storyId", null);
        return "story-form";
    }

    @PostMapping("/stories")
    String add(@ModelAttribute StoryForm form, @RequestParam(defaultValue = "/queue") String back, Model model,
            RedirectAttributes flash) {
        try {
            Story s = queue.add(form, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Added to the bottom of the queue: " + s.getConcept());
            return "redirect:" + safeBack(back);
        } catch (InvalidInputException e) {
            model.addAttribute("form", form);
            model.addAttribute("errors", e.fieldErrors());
            model.addAttribute("topics", topics.all());
            model.addAttribute("storyId", null);
            return "story-form";
        }
    }

    @GetMapping("/stories/{id}")
    String show(@PathVariable long id, Model model) {
        Story story = stories.findWithTopic(id).orElseThrow(() -> new NotFoundException("story " + id));
        model.addAttribute("story", story);
        model.addAttribute("history", changes.findByStoryIdOrderByIdAsc(id));
        model.addAttribute("queued", story.getStatus() == StoryStatus.QUEUED);
        var latest = new java.util.LinkedHashMap<String, com.storyforge.stage2.AudioLines.Line>();
        audio.forStory(id).forEach(l -> latest.put(l.lineId(), l));
        model.addAttribute("audioLines", latest.values());
        model.addAttribute("audioTrack", audio.track(id).orElse(null));
        model.addAttribute("audioFlagged", story.getStatus() == StoryStatus.NEEDS_ATTENTION
                && story.getResumeStatus() == StoryStatus.AUDIO_IN_PROGRESS);
        var shotImages = new java.util.LinkedHashMap<String, com.storyforge.stage3.ShotStore.Image>();
        shotStore.images(id).forEach(i -> {
            var cur = shotImages.get(i.shotId());
            if (cur == null || !cur.passed() && (i.passed() || i.accepted() || i.failures() <= cur.failures())) {
                shotImages.put(i.shotId(), i);
            }
        });
        var clips = new java.util.HashMap<String, String>();
        shotStore.clips(id).forEach(c -> clips.put(c.shotId(), c.assetKey()));
        model.addAttribute("shotImages", shotImages.values());
        model.addAttribute("shotClips", clips);
        model.addAttribute("visualsFlagged", story.getStatus() == StoryStatus.NEEDS_ATTENTION
                && story.getResumeStatus() == StoryStatus.VISUALS_IN_PROGRESS);
        return "story";
    }

    @GetMapping("/stories/{id}/edit")
    String edit(@PathVariable long id, Model model, RedirectAttributes flash) {
        Story story = stories.findWithTopic(id).orElseThrow(() -> new NotFoundException("story " + id));
        if (story.getStatus() != StoryStatus.QUEUED) {
            flash.addFlashAttribute("error", "This story has started, so it can't be edited.");
            return "redirect:/stories/" + id;
        }
        model.addAttribute("form", StoryForm.of(story));
        model.addAttribute("topics", topics.all());
        model.addAttribute("storyId", id);
        return "story-form";
    }

    @PostMapping("/stories/{id}")
    String update(@PathVariable long id, @ModelAttribute StoryForm form, Model model, RedirectAttributes flash) {
        try {
            queue.edit(id, form, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Saved.");
            return "redirect:/stories/" + id;
        } catch (InvalidInputException e) {
            model.addAttribute("form", form);
            model.addAttribute("errors", e.fieldErrors());
            model.addAttribute("topics", topics.all());
            model.addAttribute("storyId", id);
            return "story-form";
        }
    }

    @PostMapping("/stories/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes flash) {
        queue.delete(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Deleted.");
        return "redirect:/queue";
    }

    @PostMapping("/stories/{id}/restore")
    String restore(@PathVariable long id, RedirectAttributes flash) {
        queue.restore(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Restored to the bottom of the queue.");
        return "redirect:/queue";
    }

    @PostMapping("/stories/{id}/resume")
    String resume(@PathVariable long id, RedirectAttributes flash) {
        Story s = queue.resume(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Resumed at " + s.getStatus().dbValue() + ".");
        return "redirect:/stories/" + id;
    }

    /** Only local paths, so a crafted link can't redirect off the app. */
    static String safeBack(String back) {
        return back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/queue";
    }
}

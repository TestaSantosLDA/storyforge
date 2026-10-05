package com.storyforge.web;

import com.storyforge.stage1.Gates;
import com.storyforge.stage2.AudioLines;
import com.storyforge.stage2.Pronunciation;
import com.storyforge.story.StoryQueueService;
import com.storyforge.validation.InvalidInputException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Stage 2 needs-attention options: accept the duration, add a pronunciation, or send the script back. */
@Controller
class AudioController {

    private final AudioLines audio;
    private final Pronunciation pronunciation;
    private final Gates gates;
    private final StoryQueueService queue;

    AudioController(AudioLines audio, Pronunciation pronunciation, Gates gates, StoryQueueService queue) {
        this.audio = audio;
        this.pronunciation = pronunciation;
        this.gates = gates;
        this.queue = queue;
    }

    @PostMapping("/stories/{id}/audio/accept-duration")
    String acceptDuration(@PathVariable long id, RedirectAttributes flash) {
        audio.acceptDuration(id);
        queue.resume(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Duration accepted. Finishing the audio.");
        return "redirect:/stories/" + id;
    }

    @PostMapping("/stories/{id}/audio/pronunciation")
    String addPronunciation(@PathVariable long id, @RequestParam String word, @RequestParam String sayAs,
            RedirectAttributes flash) {
        try {
            pronunciation.put(word, sayAs);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "Give both the word and how to say it.");
            return "redirect:/stories/" + id;
        }
        queue.resume(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "\"" + word.strip() + "\" will be read as \"" + sayAs.strip()
                + "\". Retrying the failed lines.");
        return "redirect:/stories/" + id;
    }

    @PostMapping("/stories/{id}/audio/send-back")
    String sendBack(@PathVariable long id, @RequestParam String notes, RedirectAttributes flash) {
        try {
            Gates.Outcome o = gates.sendBackToScript(id, notes, Actor.LOCAL);
            flash.addFlashAttribute(o == Gates.Outcome.ARCHIVED ? "error" : "notice",
                    o == Gates.Outcome.ARCHIVED ? "That was the last allowed script rejection, so the story was archived."
                            : "Sent back to Gate B. Rewriting the script with your notes.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", e.fieldErrors().get("notes"));
        }
        return "redirect:/stories/" + id;
    }
}

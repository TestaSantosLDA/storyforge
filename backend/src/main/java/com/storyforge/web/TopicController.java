package com.storyforge.web;

import com.storyforge.character.CharacterStore;
import com.storyforge.profile.ProfileException;
import com.storyforge.profile.ProfileSnapshots;
import com.storyforge.profile.VideoProfiles;
import com.storyforge.story.StoryRepository;
import com.storyforge.topic.Topic;
import com.storyforge.topic.TopicRepository;
import com.storyforge.topic.TopicService;
import com.storyforge.validation.InvalidInputException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class TopicController {

    private final TopicService topics;
    private final TopicRepository topicRepo;
    private final StoryRepository stories;
    private final CharacterStore characters;
    private final VideoProfiles profiles;
    private final ProfileSnapshots snapshots;

    TopicController(TopicService topics, TopicRepository topicRepo, StoryRepository stories,
            CharacterStore characters, VideoProfiles profiles, ProfileSnapshots snapshots) {
        this.profiles = profiles;
        this.snapshots = snapshots;
        this.topics = topics;
        this.topicRepo = topicRepo;
        this.stories = stories;
        this.characters = characters;
    }

    @GetMapping("/topics")
    String list(Model model) {
        model.addAttribute("topics", topics.all());
        return "topics";
    }

    @PostMapping("/topics")
    String create(@RequestParam String name, @RequestParam String description, Model model,
            RedirectAttributes flash) {
        try {
            Topic t = topics.create(name, description, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Topic created.");
            return "redirect:/topics/" + t.getId();
        } catch (InvalidInputException e) {
            model.addAttribute("topics", topics.all());
            model.addAttribute("errors", e.fieldErrors());
            model.addAttribute("name", name);
            model.addAttribute("description", description);
            return "topics";
        }
    }

    @GetMapping("/topics/{id}")
    String show(@PathVariable long id, Model model) {
        Topic topic = topicRepo.findById(id).orElseThrow(() -> new NotFoundException("topic " + id));
        model.addAttribute("topic", topic);
        model.addAttribute("stories", stories.findByTopicId(id));
        model.addAttribute("characters", characters.byTopic(id));
        model.addAttribute("profiles", profiles.all());
        if (!model.containsAttribute("profileId")) {
            model.addAttribute("profileId", topic.getProfileId());
            model.addAttribute("overrides", ProfileSnapshots.overridesAsYaml(topic.getProfileOverrides()));
            model.addAttribute("lookNotes", topic.getLookNotes());
        }
        if (topic.getProfileId() != null) {
            try {
                model.addAttribute("effective", snapshots.effective(topic.getProfileId(),
                        ProfileSnapshots.fromJson(topic.getProfileOverrides())));
            } catch (ProfileException e) {
                model.addAttribute("profileProblem", e.getMessage());
            }
        }
        return "topic";
    }

    @PostMapping("/topics/{id}/profile")
    String setProfile(@PathVariable long id, @RequestParam(required = false) String profileId,
            @RequestParam(required = false) String overrides, @RequestParam(required = false) String lookNotes,
            RedirectAttributes flash) {
        try {
            topics.setProfile(id, profileId, overrides, lookNotes, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Saved. Stories already running keep the profile they started with.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("profileErrors", e.fieldErrors());
            flash.addFlashAttribute("profileId", profileId);
            flash.addFlashAttribute("overrides", overrides);
            flash.addFlashAttribute("lookNotes", lookNotes);
        }
        return "redirect:/topics/" + id;
    }

    @PostMapping("/topics/{id}/description")
    String editDescription(@PathVariable long id, @RequestParam String description, RedirectAttributes flash) {
        try {
            topics.editDescription(id, description, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Saved. Stories already running keep the description they started with.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", e.fieldErrors().get("description"));
        }
        return "redirect:/topics/" + id;
    }

    @PostMapping("/topics/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes flash) {
        topics.delete(id, Actor.LOCAL);
        flash.addFlashAttribute("notice", "Topic deleted.");
        return "redirect:/topics";
    }
}

package com.storyforge.web;

import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.stage1.Drafts;
import com.storyforge.stage1.Gates;
import com.storyforge.stage1.ScriptContent;
import com.storyforge.stage1.StoryContent;
import com.storyforge.story.Story;
import com.storyforge.story.StoryRepository;
import com.storyforge.story.StoryStatus;
import com.storyforge.validation.InvalidInputException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Gate A (story + cast) and Gate B (script) review screens (docs/pipeline-a/stage-1). */
@Controller
class GateController {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    record Check(String name, boolean pass, String feedback, String source) {
    }

    /** A scene with its lines resolved for display. */
    record SceneView(String id, String mood, String visualDescription, List<LineView> lines) {
    }

    record LineView(String id, String speaker, String text) {
    }

    private final StoryRepository stories;
    private final Drafts drafts;
    private final Gates gates;
    private final CharacterStore characters;

    GateController(StoryRepository stories, Drafts drafts, Gates gates, CharacterStore characters) {
        this.stories = stories;
        this.drafts = drafts;
        this.gates = gates;
        this.characters = characters;
    }

    @GetMapping("/stories/{id}/gate-a")
    String gateA(@PathVariable long id, Model model) {
        Story story = story(id);
        var latest = drafts.latest(id, Drafts.Kind.STORY).orElse(null);
        model.addAttribute("story", story);
        model.addAttribute("waiting", story.getStatus() == StoryStatus.AWAITING_STORY_APPROVAL);
        model.addAttribute("versions", drafts.all(id, Drafts.Kind.STORY));
        if (latest != null) {
            StoryContent content = JSON.readValue(latest.content(), StoryContent.class);
            Map<String, CharacterFile> topicCast = new HashMap<>();
            characters.byTopic(story.getTopic().getId()).forEach(c -> topicCast.put(c.id(), c));
            model.addAttribute("draft", latest);
            model.addAttribute("content", content);
            model.addAttribute("topicCast", topicCast);
            model.addAttribute("checks", checks(latest.qaResults()));
        }
        return "gate-a";
    }

    @GetMapping("/stories/{id}/gate-b")
    String gateB(@PathVariable long id, Model model) {
        Story story = story(id);
        var latest = drafts.latest(id, Drafts.Kind.SCRIPT).orElse(null);
        model.addAttribute("story", story);
        model.addAttribute("waiting", story.getStatus() == StoryStatus.AWAITING_SCRIPT_APPROVAL);
        model.addAttribute("versions", drafts.all(id, Drafts.Kind.SCRIPT));
        drafts.approved(id, Drafts.Kind.STORY).ifPresent(d ->
                model.addAttribute("approvedStory", JSON.readValue(d.content(), StoryContent.class)));
        if (latest != null) {
            ScriptContent script = JSON.readValue(latest.content(), ScriptContent.class);
            Map<String, ScriptContent.Line> lines = new HashMap<>();
            script.lines().forEach(l -> lines.put(l.id(), l));
            Map<String, String> names = new HashMap<>();
            names.put(ScriptContent.NARRATOR, "Narrator");
            characters.byTopic(story.getTopic().getId()).forEach(c -> names.put(c.id(), c.name()));
            List<SceneView> scenes = script.scenes().stream().map(sc -> new SceneView(sc.id(), sc.mood(),
                    sc.visualDescription(), sc.lineIds().stream().map(lines::get).filter(java.util.Objects::nonNull)
                            .map(l -> new LineView(l.id(), names.getOrDefault(l.speaker(), l.speaker()), l.text()))
                            .toList())).toList();
            model.addAttribute("draft", latest);
            model.addAttribute("scenes", scenes);
            model.addAttribute("wordCount", script.wordCount());
            model.addAttribute("checks", checks(latest.qaResults()));
        }
        return "gate-b";
    }

    @PostMapping("/stories/{id}/gate-{gate}/approve")
    String approve(@PathVariable long id, @PathVariable String gate, RedirectAttributes flash) {
        try {
            if (gate(gate) == Gates.Gate.STORY) {
                gates.approveStory(id, Actor.LOCAL);
                flash.addFlashAttribute("notice", "Story and cast approved. Writing the script now.");
            } else {
                gates.approveScript(id, Actor.LOCAL);
                flash.addFlashAttribute("notice", "Script approved.");
            }
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", String.join(" ", e.fieldErrors().values()));
            return "redirect:/stories/" + id + "/gate-" + gate;
        }
        return "redirect:/stories/" + id;
    }

    @PostMapping("/stories/{id}/gate-{gate}/regenerate")
    String regenerate(@PathVariable long id, @PathVariable String gate, @RequestParam String notes,
            RedirectAttributes flash) {
        try {
            Gates.Outcome o = gates.regenerate(id, gate(gate), notes, Actor.LOCAL);
            flash.addFlashAttribute(o == Gates.Outcome.ARCHIVED ? "error" : "notice",
                    o == Gates.Outcome.ARCHIVED ? "That was the last allowed rejection, so the story was archived."
                            : "Regenerating with your notes.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", e.fieldErrors().get("notes"));
            return "redirect:/stories/" + id + "/gate-" + gate;
        }
        return "redirect:/stories/" + id;
    }

    @PostMapping("/stories/{id}/gate-{gate}/kill")
    String kill(@PathVariable long id, @PathVariable String gate, RedirectAttributes flash) {
        gates.kill(id, gate(gate), Actor.LOCAL);
        flash.addFlashAttribute("notice", "Story killed and archived.");
        return "redirect:/stories/" + id;
    }

    private Story story(long id) {
        return stories.findWithTopic(id).orElseThrow(() -> new NotFoundException("story " + id));
    }

    private static Gates.Gate gate(String g) {
        return switch (g) {
            case "a" -> Gates.Gate.STORY;
            case "b" -> Gates.Gate.SCRIPT;
            default -> throw new NotFoundException("gate " + g);
        };
    }

    private static List<Check> checks(String json) {
        return json == null ? List.of() : List.of(JSON.readValue(json, Check[].class));
    }
}

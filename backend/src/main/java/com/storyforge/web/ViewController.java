package com.storyforge.web;

import com.storyforge.story.StoryRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
class ViewController {

    private final StoryRepository stories;

    ViewController(StoryRepository stories) {
        this.stories = stories;
    }

    @GetMapping("/views/{slug}")
    String view(@PathVariable String slug, Model model) {
        StatusView view;
        try {
            view = StatusView.fromSlug(slug);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(slug);
        }
        model.addAttribute("view", view);
        model.addAttribute("stories", stories.findForView(view.statuses()));
        return "view";
    }
}

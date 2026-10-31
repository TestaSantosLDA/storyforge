package com.storyforge.web;

import com.storyforge.series.ChannelForm;
import com.storyforge.series.ChannelService;
import com.storyforge.validation.InvalidInputException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Channels on the management page (docs/series/lusiadas.md C2). */
@Controller
class ChannelController {

    private final ChannelService channels;

    ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @GetMapping("/channels")
    String list(Model model) {
        // Each channel with its form values, so the page needs no static calls.
        model.addAttribute("rows", channels.all().stream().map(c -> new Row(c, ChannelForm.of(c))).toList());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ChannelForm.blank());
        }
        return "channels";
    }

    record Row(com.storyforge.series.Channel channel, ChannelForm form) {
    }

    @PostMapping("/channels")
    String create(@ModelAttribute ChannelForm form, RedirectAttributes flash) {
        try {
            channels.create(form, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Channel created. Pick it on a topic's page to publish there.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("errors", e.fieldErrors());
            flash.addFlashAttribute("form", form);
        }
        return "redirect:/channels";
    }

    @PostMapping("/channels/{id}")
    String edit(@PathVariable long id, @ModelAttribute ChannelForm form, RedirectAttributes flash) {
        try {
            channels.edit(id, form, Actor.LOCAL);
            flash.addFlashAttribute("notice", "Saved. Stories already running keep the channel they started with.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("editErrors", e.fieldErrors());
            flash.addFlashAttribute("editId", id);
        }
        return "redirect:/channels";
    }
}

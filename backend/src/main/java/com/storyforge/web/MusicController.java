package com.storyforge.web;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage4.Assembler;
import com.storyforge.stage4.MusicLibrary;
import com.storyforge.validation.InvalidInputException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The music library page: list tracks with their licenses, import a track with its license file. */
@Controller
class MusicController {

    private final MusicLibrary library;
    private final Assembler assembler;

    MusicController(MusicLibrary library, StoryforgeProperties props) {
        this.library = library;
        this.assembler = new Assembler(props);
    }

    @GetMapping("/music")
    String list(Model model) {
        model.addAttribute("tracks", library.all());
        model.addAttribute("licenses", MusicLibrary.LICENSES);
        model.addAttribute("moods", List.of("calm", "playful", "tense_but_gentle", "happy_ending"));
        return "music";
    }

    @PostMapping("/music")
    String add(@RequestParam String title, @RequestParam MultipartFile audio, @RequestParam String sourceUrl,
            @RequestParam String license, @RequestParam MultipartFile licenseFile,
            @RequestParam(required = false) String attribution, @RequestParam(required = false) List<String> moods,
            RedirectAttributes flash) throws IOException {
        double seconds = 0;
        if (!audio.isEmpty()) {
            Path tmp = Files.createTempFile("storyforge-music-", ".tmp");
            try {
                audio.transferTo(tmp);
                seconds = assembler.duration(tmp);
            } catch (Assembler.AssemblyException e) {
                flash.addFlashAttribute("error", "That file couldn't be read as audio.");
                return "redirect:/music";
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        try {
            library.importTrack(title, audio.getBytes(), audio.getOriginalFilename(), seconds, moods == null ? List.of() : moods,
                    sourceUrl, license, licenseFile.getBytes(), licenseFile.getOriginalFilename(), attribution);
            flash.addFlashAttribute("notice", "Added \"" + title.strip() + "\" to the music library.");
        } catch (InvalidInputException e) {
            flash.addFlashAttribute("error", String.join(" ", e.fieldErrors().values()));
        }
        return "redirect:/music";
    }
}

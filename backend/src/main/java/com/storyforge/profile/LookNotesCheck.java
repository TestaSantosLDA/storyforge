package com.storyforge.profile;

import com.storyforge.config.StoryforgeProperties;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Refuses free text that names a studio, franchise or existing character (CLAUDE.md licensing rules; Pipeline D
 * "Look notes"). The names come from config ({@code storyforge.profiles.banned-names}); a topic's public-domain
 * allowlist is a later series field.
 */
@Component
public class LookNotesCheck {

    private final List<String> banned;

    @Autowired
    LookNotesCheck(StoryforgeProperties props) {
        this(props.profiles().bannedNames());
    }

    LookNotesCheck(List<String> banned) {
        this.banned = banned == null ? List.of() : List.copyOf(banned);
    }

    /** The first banned name found in the text, if any. */
    public Optional<String> findBanned(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : banned) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name.toLowerCase(Locale.ROOT))
                    + "(?![\\p{L}\\p{N}])");
            if (p.matcher(lower).find()) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /** The message shown when a note is refused. */
    public static String refusal(String name) {
        return "Names \"" + name + "\". Notes may not name a studio, franchise or existing character; describe the "
                + "look by its ingredients instead (colours, line, shapes, motion).";
    }
}

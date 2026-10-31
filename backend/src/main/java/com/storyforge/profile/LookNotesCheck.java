package com.storyforge.profile;

import com.storyforge.config.StoryforgeProperties;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses free text that names a studio, franchise or existing character (CLAUDE.md licensing rules; Pipeline D
 * "Look notes"). The names come from config ({@code storyforge.profiles.banned-names}). A name on the topic's
 * public-domain allowlist is let through (docs/series/lusiadas.md C9).
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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The first banned name found in the text, if any. */
    public Optional<String> findBanned(String text) {
        return findBanned(text, List.of());
    }

    /** The first banned name found in the text that isn't on the allowlist (compared case-insensitively). */
    public Optional<String> findBanned(String text, List<String> allowlist) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : banned) {
            if (name == null || name.isBlank() || allowlist.stream().anyMatch(name::equalsIgnoreCase)) {
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

    /** A topic's allowlist from its JSON column; empty when there is none. */
    public static List<String> allowlist(String json) {
        return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<String>>() { });
    }

    public static String allowlistJson(List<String> names) {
        return JSON.writeValueAsString(names);
    }

    /** The message shown when a note is refused. */
    public static String refusal(String name) {
        return "Names \"" + name + "\". Notes may not name a studio, franchise or existing character; describe the "
                + "look by its ingredients instead (colours, line, shapes, motion).";
    }
}

package com.storyforge.stage1;

import com.storyforge.character.CharacterFile;
import com.storyforge.character.CharacterStore;
import com.storyforge.config.StoryforgeProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What Stage 1 knows about characters: the topic's cast, the channel-wide roster, and which voices are free.
 * Also the deterministic cast checks (voice and name distinctness), which don't need Claude.
 */
@Component
public class Cast {

    private final CharacterStore characters;
    private final StoryforgeProperties.Voices voices;

    Cast(CharacterStore characters, StoryforgeProperties props) {
        this.characters = characters;
        this.voices = props.voices();
    }

    public List<CharacterFile> topic(long topicId) {
        return characters.byTopic(topicId);
    }

    public List<CharacterFile> roster() {
        return characters.all();
    }

    public List<String> freeVoices(long topicId) {
        Set<String> used = topic(topicId).stream().map(Cast::voiceId).collect(Collectors.toSet());
        return voices.available().stream()
                .filter(v -> !v.equals(voices.narrator()) && !used.contains(v)).toList();
    }

    static String voiceId(CharacterFile c) {
        return c.voice() == null ? null : String.valueOf(c.voice().get("id"));
    }

    static String describe(List<CharacterFile> cs) {
        if (cs.isEmpty()) {
            return "(none)";
        }
        return cs.stream().map(c -> "- " + c.id() + " (" + c.name() + "): " + oneLine(c.visualDescription())
                        + (c.personality() == null ? "" : " Personality: " + oneLine(c.personality())))
                .collect(Collectors.joining("\n"));
    }

    /** Deterministic Step 1A checks: reused ids exist, ids/names/voices don't clash. */
    public List<QaCheck> check(StoryContent story, long topicId) {
        List<CharacterFile> topicCast = topic(topicId);
        List<CharacterFile> roster = roster();
        Set<String> topicIds = topicCast.stream().map(CharacterFile::id).collect(Collectors.toSet());

        List<String> unknownReused = story.reusedCharacters().stream().map(StoryContent.ReusedCharacter::id)
                .filter(id -> !topicIds.contains(id)).toList();

        Set<String> clashes = new LinkedHashSet<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (StoryContent.NewCharacter n : story.newCharacters()) {
            for (CharacterFile existing : roster) {
                if (existing.id().equals(n.id())) {
                    clashes.add("id \"" + n.id() + "\" is already used by " + existing.name());
                } else if (similarNames(existing.name(), n.name())) {
                    clashes.add("\"" + n.name() + "\" is too close to the existing character \"" + existing.name() + "\"");
                }
            }
            if (!seenNames.add(norm(n.name()))) {
                clashes.add("two new characters are both called \"" + n.name() + "\"");
            }
        }

        List<String> free = freeVoices(topicId);
        Set<String> voiceProblems = new LinkedHashSet<>();
        Set<String> taken = new LinkedHashSet<>();
        for (StoryContent.NewCharacter n : story.newCharacters()) {
            if (!free.contains(n.proposedVoice())) {
                voiceProblems.add(n.name() + "'s voice \"" + n.proposedVoice() + "\" is not free in this universe; pick from: "
                        + String.join(", ", free));
            } else if (!taken.add(n.proposedVoice())) {
                voiceProblems.add(n.name() + " shares voice \"" + n.proposedVoice() + "\" with another new character");
            }
        }

        return List.of(
                QaCheck.code("reused_characters_exist", unknownReused.isEmpty(),
                        "these reused ids are not characters of this universe: " + unknownReused),
                QaCheck.code("name_distinctness", clashes.isEmpty(), String.join("; ", clashes)),
                QaCheck.code("voice_distinctness", voiceProblems.isEmpty(), String.join("; ", voiceProblems)));
    }

    /** Same after normalising, or within edit distance 2 for short names (e.g. "Pip" vs "Pipp"). */
    static boolean similarNames(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.equals(y)) {
            return true;
        }
        int d = levenshtein(x, y);
        return d <= 1 || (d == 2 && Math.max(x.length(), y.length()) >= 6);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}

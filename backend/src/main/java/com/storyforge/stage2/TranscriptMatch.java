package com.storyforge.stage2;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Word match between a script line and Whisper's transcript, after normalising case, punctuation and numbers
 * (Stage 2 "Transcript match"). Lenient where Whisper is predictably different from a correct reading: UK/US
 * spellings ("harbour" / "harbor"), invented names, which it spells by ear ("Tamsin" / "Tamsen"), words that sound
 * exactly alike ("quay" / "key") and a past-tense ending swallowed by the next word ("checked the" / "check the").
 * Score = matched words / the longer of the two word lists, so dropped and extra words both cost.
 */
public final class TranscriptMatch {

    private static final Map<String, String> NUMBERS = Map.ofEntries(
            Map.entry("0", "zero"), Map.entry("1", "one"), Map.entry("2", "two"), Map.entry("3", "three"),
            Map.entry("4", "four"), Map.entry("5", "five"), Map.entry("6", "six"), Map.entry("7", "seven"),
            Map.entry("8", "eight"), Map.entry("9", "nine"), Map.entry("10", "ten"), Map.entry("11", "eleven"),
            Map.entry("12", "twelve"), Map.entry("20", "twenty"), Map.entry("100", "hundred"));

    /** Words a listener can't tell apart; Whisper picks the common spelling. Both sides fold to the same key. */
    private static final Map<String, String> SOUNDS_LIKE = Map.ofEntries(
            Map.entry("quay", "key"), Map.entry("quays", "keys"), Map.entry("tale", "tail"), Map.entry("tales", "tails"),
            Map.entry("knight", "night"), Map.entry("knights", "nights"), Map.entry("sea", "see"),
            Map.entry("whole", "hole"), Map.entry("hare", "hair"), Map.entry("hares", "hairs"),
            Map.entry("dear", "deer"), Map.entry("bough", "bow"), Map.entry("boughs", "bows"), Map.entry("aloud", "allowed"),
            Map.entry("flour", "flower"), Map.entry("flours", "flowers"), Map.entry("knew", "new"), Map.entry("know", "no"),
            Map.entry("right", "write"), Map.entry("sun", "son"), Map.entry("their", "there"), Map.entry("theyre", "there"),
            Map.entry("weather", "whether"), Map.entry("wood", "would"), Map.entry("woods", "woulds"),
            Map.entry("road", "rode"), Map.entry("pair", "pear"), Map.entry("berry", "bury"), Map.entry("mail", "male"),
            Map.entry("plain", "plane"), Map.entry("peace", "piece"), Map.entry("wait", "weight"), Map.entry("way", "weigh"));

    private TranscriptMatch() {
    }

    public static double score(String script, String transcript, Set<String> names) {
        List<String> a = words(script);
        List<String> b = words(transcript);
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        Set<String> nameWords = names.stream().flatMap(n -> words(n).stream()).collect(Collectors.toSet());
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                lcs[i][j] = same(a.get(i - 1), b.get(j - 1), nameWords)
                        ? lcs[i - 1][j - 1] + 1 : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
            }
        }
        return (double) lcs[a.size()][b.size()] / Math.max(a.size(), b.size());
    }

    static List<String> words(String s) {
        String plain = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("’", "'").replaceAll("'s\\b", "s").replaceAll("[^a-z0-9' ]", " ")
                .replace("'", "");
        List<String> out = new ArrayList<>();
        for (String w : plain.trim().split("\\s+")) {
            if (!w.isEmpty()) {
                out.add(NUMBERS.getOrDefault(w, w));
            }
        }
        return out;
    }

    static boolean same(String scriptWord, String heard, Set<String> nameWords) {
        if (scriptWord.equals(heard) || us(scriptWord).equals(us(heard)) || sound(scriptWord).equals(sound(heard))) {
            return true;
        }
        if (heard.length() >= 4 && (scriptWord.equals(heard + "ed") || scriptWord.equals(heard + "d"))) {
            return true; // "-ed" before a word starting with t/d is often inaudible: "checked the" heard as "check the"
        }
        int d = distance(scriptWord, heard);
        if (nameWords.contains(scriptWord)) {
            return d <= 2 && heard.length() >= 3;
        }
        return d <= 1 && Math.min(scriptWord.length(), heard.length()) >= 6;
    }

    private static String sound(String w) {
        return SOUNDS_LIKE.getOrDefault(w, w);
    }

    /** Folds common UK spellings to US ones: colour/color, centre/center, realise/realize, travelled/traveled. */
    static String us(String w) {
        return w.replaceAll("our(s?)$", "or$1").replaceAll("([a-z])re$", "$1er").replaceAll("is(e|ed|es|ing)$", "iz$1")
                .replaceAll("ll(ed|ing|er)$", "l$1");
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}

package com.storyforge.vertical;

import com.storyforge.config.StoryforgeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Word-by-word captions for a vertical video (docs/pipeline-c C3, docs/pipeline-d D4): 1 to {@code maxWords} words
 * on screen, the word being spoken highlighted, centred above the bottom safe zone. Built as an ASS subtitle file that
 * FFmpeg burns in. Same look as the clips captions (docs/pipeline-b/clips-from-any-video.md), plus a group also ends
 * at punctuation so a caption never runs across two sentences.
 */
@Component
public class WordCaptions {

    private static final double MIN_EVENT_SEC = 0.04;

    private final StoryforgeProperties props;

    public WordCaptions(StoryforgeProperties props) {
        this.props = props;
    }

    /** The groups shown one after another; each is 1 to {@code maxWords} words. */
    public List<List<TimedWord>> groups(List<TimedWord> words) {
        int max = props.vertical().captions().maxWords();
        List<List<TimedWord>> out = new ArrayList<>();
        List<TimedWord> group = new ArrayList<>();
        for (TimedWord w : words) {
            group.add(w);
            if (group.size() == max || endsClause(w.text())) {
                out.add(List.copyOf(group));
                group.clear();
            }
        }
        if (!group.isEmpty()) {
            out.add(List.copyOf(group));
        }
        return out;
    }

    public String ass(List<TimedWord> words) {
        var v = props.vertical();
        var c = v.captions();
        StringBuilder a = new StringBuilder();
        a.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(v.width()).append("\nPlayResY: ")
                .append(v.height()).append("\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, Bold, BorderStyle, ")
                .append("Outline, Shadow, Alignment, MarginL, MarginR, MarginV\n")
                .append(String.format(Locale.ROOT, "Style: Caption,%s,%d,%s,&H00000000,&H80000000,-1,1,6,0,2,%d,%d,%d\n",
                        c.font(), c.fontSize(), colour(c.colour()), v.safeZone().left(), v.safeZone().right(),
                        c.bottomMargin()));
        a.append("\n[Events]\nFormat: Layer, Start, End, Style, MarginL, MarginR, MarginV, Effect, Text\n");
        List<List<TimedWord>> groups = groups(words);
        for (int g = 0; g < groups.size(); g++) {
            List<TimedWord> group = groups.get(g);
            Double nextGroupStart = g + 1 < groups.size() ? groups.get(g + 1).getFirst().start() : null;
            for (int k = 0; k < group.size(); k++) {
                TimedWord w = group.get(k);
                double end = k + 1 < group.size() ? group.get(k + 1).start()
                        : nextGroupStart != null ? nextGroupStart : w.end();
                StringBuilder text = new StringBuilder();
                for (int n = 0; n < group.size(); n++) {
                    String word = escape(display(group.get(n).text()));
                    text.append(n == 0 ? "" : " ").append(n == k
                            ? "{\\c" + colour(c.highlight()) + "}" + word + "{\\c" + colour(c.colour()) + "}"
                            : word);
                }
                a.append("Dialogue: 0,").append(time(w.start())).append(',')
                        .append(time(Math.max(end, w.start() + MIN_EVENT_SEC))).append(",Caption,0,0,0,,")
                        .append(text).append('\n');
            }
        }
        return a.toString();
    }

    /** Captions are upper case, without the trailing comma or full stop that only marks where the group ends. */
    static String display(String word) {
        return word.strip().replaceAll("[,.;:]+$", "").toUpperCase(Locale.ROOT);
    }

    private static boolean endsClause(String word) {
        return word.strip().matches(".*[,.;:?!…]$");
    }

    /** Overrides and line breaks can't come from the transcript. */
    static String escape(String text) {
        return text.replace("\\", "/").replace("{", "(").replace("}", ")").replace("\n", " ");
    }

    static String time(double seconds) {
        long cs = Math.round(seconds * 100);
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100);
    }

    /** {@code #RRGGBB} to ASS {@code &H00BBGGRR&}. */
    static String colour(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!h.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("colour must be #RRGGBB, not " + hex);
        }
        return ("&H00" + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2) + "&").toUpperCase(Locale.ROOT);
    }
}

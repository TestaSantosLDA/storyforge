package com.storyforge.vertical;

import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage1.QaCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Code QA rows every vertical video adds to Stage 4's (docs/pipeline-c C3, docs/pipeline-d D4): under the length
 * limit, captions say what the narration says, and captions sit inside the safe zone.
 */
@Component
public class VerticalQa {

    private final StoryforgeProperties props;

    public VerticalQa(StoryforgeProperties props) {
        this.props = props;
    }

    public QaCheck length(double seconds) {
        double max = props.vertical().maxDuration().toMillis() / 1000.0;
        return QaCheck.code("length", seconds < max,
                String.format(Locale.ROOT, "%.1f s; a Short must be under %.0f s", seconds, max));
    }

    /**
     * Every caption word, in order, is a word of the narration transcript (Whisper's, not the script's), so captions
     * never show a line the voice did not say.
     */
    public QaCheck captionsMatchTranscript(List<TimedWord> captions, List<String> transcript) {
        List<String> said = transcript.stream().map(VerticalQa::norm).filter(w -> !w.isEmpty()).toList();
        List<String> missing = new ArrayList<>();
        int at = 0;
        for (TimedWord w : captions) {
            String word = norm(w.text());
            if (word.isEmpty()) {
                continue;
            }
            int found = said.subList(at, said.size()).indexOf(word);
            if (found < 0) {
                missing.add(w.text());
            } else {
                at += found + 1;
            }
        }
        return QaCheck.code("captions", missing.isEmpty(), "not in the narration transcript: " + missing);
    }

    /**
     * Captions sit above the bottom safe zone and, at two lines, stay below the top one. Text drawn by a scene is
     * checked by that scene's layout check, not here.
     */
    public QaCheck captionsInSafeZone() {
        var v = props.vertical();
        var c = v.captions();
        int baseline = v.height() - c.bottomMargin();
        int top = baseline - 2 * Math.round(c.fontSize() * 1.2f);
        boolean ok = c.bottomMargin() >= v.safeZone().bottom() && top >= v.safeZone().top();
        return QaCheck.code("caption safe zone", ok, String.format(Locale.ROOT,
                "captions span y %d-%d; the safe zone is y %d-%d", top, baseline, v.safeZone().top(),
                v.height() - v.safeZone().bottom()));
    }

    private static String norm(String word) {
        return word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}']", "");
    }
}

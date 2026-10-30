package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.clips.ClipJobs.Line;
import com.storyforge.clips.ClipJobs.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** The words of a slice, for prompts and the review screen. */
public final class ClipText {

    private ClipText() {
    }

    static String spoken(Source s, String startLine, String endLine) {
        List<Line> out = new ArrayList<>();
        boolean in = false;
        for (Line l : s.lines()) {
            in = in || l.lineId().equals(startLine);
            if (in) {
                out.add(l);
            }
            if (l.lineId().equals(endLine)) {
                break;
            }
        }
        return out.stream().map(Line::text).collect(Collectors.joining(" "));
    }

    public static String spoken(Source s, Clip c) {
        return spoken(s, c.startLineId(), c.endLineId());
    }
}

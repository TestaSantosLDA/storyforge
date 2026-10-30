package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Line;
import com.storyforge.voice.WordTranscriber.Word;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Groups Whisper's words into the lines B1 picks from: sentences, or pauses of {@code pause} seconds or more. */
final class Transcript {

    private Transcript() {
    }

    static List<Line> lines(List<Word> words, double pause, double maxLine) {
        List<Line> out = new ArrayList<>();
        List<Word> line = new ArrayList<>();
        for (Word w : words) {
            if (!line.isEmpty()) {
                Word last = line.getLast();
                boolean sentenceEnd = last.text().matches(".*[.!?…。！？][\"'”)]?$");
                if (sentenceEnd || w.start() - last.end() >= pause || w.end() - line.getFirst().start() > maxLine) {
                    out.add(line(out.size() + 1, line));
                    line = new ArrayList<>();
                }
            }
            line.add(w);
        }
        if (!line.isEmpty()) {
            out.add(line(out.size() + 1, line));
        }
        return out;
    }

    private static Line line(int n, List<Word> words) {
        return new Line(String.format(Locale.ROOT, "L%04d", n), words.getFirst().start(), words.getLast().end(),
                String.join(" ", words.stream().map(Word::text).toList()));
    }

    /** Seconds of speech: the lines added up, so a long silence or music between them doesn't count. */
    static double speech(List<Line> lines) {
        return lines.stream().mapToDouble(l -> l.end() - l.start()).sum();
    }

    /** Manifest QA: lines in order, none overlapping. */
    static boolean ordered(List<Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.end() < l.start() || i > 0 && l.start() < lines.get(i - 1).end()) {
                return false;
            }
        }
        return true;
    }

    /** The cut within {@code reach} of {@code t}, or {@code t}. */
    static double snap(double t, List<Double> cuts, double reach) {
        double best = t;
        double gap = reach;
        for (double c : cuts) {
            if (Math.abs(c - t) <= gap) {
                best = c;
                gap = Math.abs(c - t);
            }
        }
        return best;
    }
}

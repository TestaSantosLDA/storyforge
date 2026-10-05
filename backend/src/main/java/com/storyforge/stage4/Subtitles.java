package com.storyforge.stage4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code .srt} file from the exact script text and the timing manifest (no speech recognition). Lines over
 * {@link #MAX_CHARS} characters fill two rows word by word; text too long for one two-row cue is split into
 * consecutive cues, timed in proportion to their length.
 */
public final class Subtitles {

    public static final int MAX_CHARS = 42;

    public record Cue(double start, double end, List<String> rows) {
    }

    private Subtitles() {
    }

    /** One spoken line with its time in the final video (intro offset already added). */
    public record Spoken(String text, double start, double end) {
    }

    public static List<Cue> cues(List<Spoken> lines) {
        List<Cue> out = new ArrayList<>();
        for (Spoken l : lines) {
            List<List<String>> chunks = chunks(l.text().strip());
            int total = chunks.stream().mapToInt(Subtitles::length).sum();
            double t = l.start();
            for (int i = 0; i < chunks.size(); i++) {
                List<String> c = chunks.get(i);
                double end = i == chunks.size() - 1 ? l.end() : t + (l.end() - l.start()) * length(c) / total;
                out.add(new Cue(t, end, c));
                t = end;
            }
        }
        return out;
    }

    private static int length(List<String> rows) {
        return String.join(" ", rows).length();
    }

    /**
     * Cues of one or two rows, each row filled word by word up to MAX_CHARS (so no row can ever be too long). A
     * sentence that ends on a full cue closes it, so cues break at sentence ends where they can. A single word longer
     * than a row gets a row of its own.
     */
    static List<List<String>> chunks(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?…])\s+")) {
            for (String word : sentence.split("\s+")) {
                if (row.length() > 0 && row.length() + 1 + word.length() > MAX_CHARS) {
                    rows.add(row.toString());
                    row.setLength(0);
                    if (rows.size() == 2) {
                        out.add(List.copyOf(rows));
                        rows.clear();
                    }
                }
                row.append(row.length() == 0 ? "" : " ").append(word);
            }
            if (!rows.isEmpty() && row.length() > 0) { // the sentence filled a second row: close the cue here
                rows.add(row.toString());
                row.setLength(0);
                out.add(List.copyOf(rows));
                rows.clear();
            }
        }
        if (row.length() > 0) {
            rows.add(row.toString());
        }
        if (!rows.isEmpty()) {
            out.add(List.copyOf(rows));
        }
        return out;
    }

    public static String srt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            sb.append(i + 1).append('\n').append(time(c.start())).append(" --> ").append(time(c.end())).append('\n');
            c.rows().forEach(r -> sb.append(r).append('\n'));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String time(double seconds) {
        long ms = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60,
                ms % 1000);
    }
}

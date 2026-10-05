package com.storyforge.stage4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code .srt} file from the exact script text and the timing manifest (no speech recognition). Lines over
 * {@link #MAX_CHARS} characters are wrapped onto two rows; text too long for one two-row cue is split into
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
            List<String> chunks = chunks(l.text().strip());
            int total = chunks.stream().mapToInt(String::length).sum();
            double t = l.start();
            for (int i = 0; i < chunks.size(); i++) {
                String c = chunks.get(i);
                double end = i == chunks.size() - 1 ? l.end() : t + (l.end() - l.start()) * c.length() / total;
                out.add(new Cue(t, end, wrap(c)));
                t = end;
            }
        }
        return out;
    }

    /** Pieces that each fit on two rows, broken at sentence ends where possible, else at word boundaries. */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?…])\\s+")) {
            for (String word : sentence.split("\\s+")) {
                if (cur.length() > 0 && cur.length() + 1 + word.length() > 2 * MAX_CHARS) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(cur.length() == 0 ? "" : " ").append(word);
            }
            if (cur.length() > MAX_CHARS) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** One or two rows of at most MAX_CHARS, split near the middle at a space. */
    static List<String> wrap(String chunk) {
        if (chunk.length() <= MAX_CHARS) {
            return List.of(chunk);
        }
        int mid = chunk.length() / 2;
        int split = -1;
        for (int d = 0; d < chunk.length(); d++) {
            if (mid - d > 0 && chunk.charAt(mid - d) == ' ' && mid - d <= MAX_CHARS && chunk.length() - (mid - d) - 1 <= MAX_CHARS) {
                split = mid - d;
                break;
            }
            if (mid + d < chunk.length() && chunk.charAt(mid + d) == ' ' && mid + d <= MAX_CHARS
                    && chunk.length() - (mid + d) - 1 <= MAX_CHARS) {
                split = mid + d;
                break;
            }
        }
        if (split < 0) {
            split = chunk.lastIndexOf(' ', MAX_CHARS);
        }
        return split < 0 ? List.of(chunk) : List.of(chunk.substring(0, split), chunk.substring(split + 1));
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

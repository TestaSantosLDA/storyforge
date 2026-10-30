package com.storyforge.clips;

import com.storyforge.clips.ClipJobs.Clip;
import com.storyforge.clips.ClipJobs.Source;
import com.storyforge.config.StoryforgeProperties;
import com.storyforge.stage4.Assembler;
import com.storyforge.voice.WordTranscriber.Word;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The B2 reframe variant in FFmpeg: real footage cropped or fitted to vertical (never re-generated), the original audio
 * normalised to the Shorts target, word-by-word captions, hook title and credit burned in. Uses no GPU.
 */
class ClipRenderer {

    private static final int FONT_CAPTION = 84;
    private static final int FONT_HOOK = 76;
    private static final int FONT_CREDIT = 36;
    private static final int CAPTION_MARGIN = 460;
    private static final int HOOK_MARGIN = 280;
    private static final int CREDIT_MARGIN = 300;
    private static final int WORDS_ON_SCREEN = 3;

    private final StoryforgeProperties props;

    ClipRenderer(StoryforgeProperties props) {
        this.props = props;
    }

    /** Renders into {@code out}. {@code closingLine} is optional; {@code credit} is null for the owner's videos. */
    void render(Source source, Path file, Clip clip, String credit, Path closingLine, Path workDir, Path out) {
        var c = props.clips();
        int fps = props.visuals().fps();
        double length = clip.endSec() - clip.startSec();
        try {
            Files.writeString(workDir.resolve("clip.ass"), ass(words(source, clip), clip.hookTitle(), credit),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> cmd = new ArrayList<>(List.of(props.media().ffmpeg(), "-y", "-loglevel", "error",
                "-ss", num(clip.startSec()), "-t", num(length), "-i", file.toString()));
        double closing = 0;
        if (closingLine != null) {
            closing = new Assembler(props).duration(closingLine);
            cmd.addAll(List.of("-i", closingLine.toString()));
        }
        StringBuilder f = new StringBuilder();
        f.append(reframe(source, clip.reframeMode(), clip.cropOffset(), c.width(), c.height()));
        f.append(String.format(Locale.ROOT, "[base]fps=%d,setsar=1,ass=clip.ass,format=yuv420p", fps));
        f.append(closing > 0 ? String.format(Locale.ROOT, ",tpad=stop_mode=clone:stop_duration=%.3f", closing) : "");
        f.append("[video];");
        if (closing > 0) {
            f.append("[0:a]aformat=sample_rates=48000:channel_layouts=stereo[a0];")
                    .append("[1:a]aformat=sample_rates=48000:channel_layouts=stereo[a1];")
                    .append("[a0][a1]concat=n=2:v=0:a=1[mix];[mix]");
        } else {
            f.append("[0:a]");
        }
        f.append(String.format(Locale.ROOT, "loudnorm=I=%.1f:TP=-1.5:LRA=11,aresample=48000[audio]", c.loudnessTarget()));
        cmd.addAll(List.of("-filter_complex", f.toString(), "-map", "[video]", "-map", "[audio]",
                "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", String.valueOf(fps),
                "-c:a", "aac", "-b:a", "192k", "-t", num(length + closing), "-movflags", "+faststart", out.toString()));
        // Run from the work folder: FFmpeg's ass filter can't take a Windows path with its drive colon.
        Assembler.run(cmd, 1800, workDir);
    }

    /** The filter that makes {@code [base]}. A vertical source passes through; only its size is fitted. */
    static String reframe(Source s, String mode, Double offset, int w, int h) {
        if (s.vertical()) {
            return String.format(Locale.ROOT, "[0:v]scale=%d:%d:force_original_aspect_ratio=decrease,"
                    + "pad=%d:%d:(ow-iw)/2:(oh-ih)/2[base];", w, h, w, h);
        }
        return switch (mode) {
            case "centre_crop" -> crop(0.5, w, h);
            case "crop_offset" -> crop(offset == null ? 0.5 : offset, w, h);
            default -> String.format(Locale.ROOT, "[0:v]split[wide][fill];"
                    + "[fill]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=30:3[back];"
                    + "[wide]scale=%d:-2[front];[back][front]overlay=(W-w)/2:(H-h)/2[base];", w, h, w, h, w);
        };
    }

    private static String crop(double position, int w, int h) {
        return String.format(Locale.ROOT, "[0:v]crop=ih*%d/%d:ih:(iw-ow)*%.3f:0,scale=%d:%d[base];", w, h, position, w, h);
    }

    /** The words of the slice, in clip time. */
    static List<Word> words(Source s, Clip c) {
        return s.words().stream().filter(w -> w.end() > c.startSec() && w.start() < c.endSec())
                .map(w -> new Word(w.text(), Math.max(0, w.start() - c.startSec()),
                        Math.min(c.endSec(), w.end()) - c.startSec())).toList();
    }

    /** Word-by-word captions (1-3 words on screen, the current one highlighted), hook title and credit. */
    String ass(List<Word> words, String hook, String credit) {
        var c = props.clips();
        StringBuilder a = new StringBuilder();
        a.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(c.width()).append("\nPlayResY: ")
                .append(c.height()).append("\nWrapStyle: 0\n\n[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, Bold, BorderStyle, ")
                .append("Outline, Shadow, Alignment, MarginL, MarginR, MarginV\n");
        style(a, "Caption", FONT_CAPTION, 1, 2, CAPTION_MARGIN);
        style(a, "Hook", FONT_HOOK, 1, 8, HOOK_MARGIN);
        style(a, "Credit", FONT_CREDIT, 0, 2, CREDIT_MARGIN);
        a.append("\n[Events]\nFormat: Layer, Start, End, Style, MarginL, MarginR, MarginV, Effect, Text\n");
        for (int i = 0; i < words.size(); i += WORDS_ON_SCREEN) {
            List<Word> group = words.subList(i, Math.min(words.size(), i + WORDS_ON_SCREEN));
            for (int k = 0; k < group.size(); k++) {
                double end = k + 1 < group.size() ? group.get(k + 1).start()
                        : i + group.size() < words.size() ? words.get(i + group.size()).start()
                        : group.get(k).end();
                StringBuilder text = new StringBuilder();
                for (int n = 0; n < group.size(); n++) {
                    String word = escape(group.get(n).text());
                    text.append(n == 0 ? "" : " ").append(n == k ? "{\\c&H0000D7FF&}" + word + "{\\c&H00FFFFFF&}" : word);
                }
                event(a, group.get(k).start(), Math.max(end, group.get(k).start() + 0.04), "Caption", text.toString());
            }
        }
        if (hook != null && !hook.isBlank()) {
            event(a, 0, c.hookSeconds(), "Hook", escape(hook));
        }
        if (credit != null) {
            double end = words.isEmpty() ? c.hookSeconds() : words.getLast().end();
            event(a, 0, end, "Credit", escape(credit));
        }
        return a.toString();
    }

    private static void style(StringBuilder a, String name, int size, int bold, int align, int marginV) {
        a.append(String.format(Locale.ROOT, "Style: %s,Arial,%d,&H00FFFFFF,&H00000000,&H00000000,%d,1,5,0,%d,80,80,%d\n",
                name, size, bold == 1 ? -1 : 0, align, marginV));
    }

    private static void event(StringBuilder a, double start, double end, String style, String text) {
        a.append("Dialogue: 0,").append(time(start)).append(',').append(time(end)).append(',').append(style)
                .append(",0,0,0,,").append(text).append('\n');
    }

    /** Overrides and line breaks can't come from the transcript. */
    static String escape(String text) {
        return text.replace("\\", "/").replace("{", "(").replace("}", ")").replace("\n", " ");
    }

    static String time(double seconds) {
        long cs = Math.round(seconds * 100);
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}

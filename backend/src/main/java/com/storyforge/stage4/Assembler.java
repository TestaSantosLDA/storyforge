package com.storyforge.stage4;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The FFmpeg render for Stage 4: intro, shot clips in order, end card; narration on top, the music bed ducked under
 * speech with a sidechain compressor, the whole mix normalised to the YouTube loudness target; H.264 + AAC.
 */
public class Assembler {

    /** A stretch of music under one or more scenes, in seconds of the final video. */
    public record MusicSegment(Path track, double start, double end) {
    }

    public record Plan(Path intro, double introSec, List<Path> clips, Path endCard, double endCardSec, Path narration,
            double narrationSec, List<MusicSegment> music, double musicVolumeDb) {
    }

    public record Probe(double seconds, int width, int height, double fps, String videoCodec, String audioCodec) {
    }

    private final StoryforgeProperties props;

    public Assembler(StoryforgeProperties props) {
        this.props = props;
    }

    public void render(Plan p, Path out) {
        var v = props.visuals();
        List<String> cmd = new ArrayList<>(List.of(props.media().ffmpeg(), "-y", "-loglevel", "error"));
        List<Path> videos = new ArrayList<>();
        if (p.intro() != null) {
            videos.add(p.intro());
        }
        videos.addAll(p.clips());
        if (p.endCard() != null) {
            videos.add(p.endCard());
        }
        videos.forEach(f -> cmd.addAll(List.of("-i", f.toString())));
        int narrationIn = videos.size();
        cmd.addAll(List.of("-i", p.narration().toString()));
        int firstMusic = narrationIn + 1;
        for (MusicSegment m : p.music()) {
            cmd.addAll(List.of("-stream_loop", "-1", "-i", m.track().toString()));
        }

        StringBuilder f = new StringBuilder();
        for (int i = 0; i < videos.size(); i++) {
            f.append(String.format(Locale.ROOT, "[%d:v]scale=%d:%d:force_original_aspect_ratio=decrease,"
                            + "pad=%d:%d:(ow-iw)/2:(oh-ih)/2,fps=%d,setsar=1,format=yuv420p[v%d];",
                    i, v.outputWidth(), v.outputHeight(), v.outputWidth(), v.outputHeight(), v.fps(), i));
        }
        for (int i = 0; i < videos.size(); i++) {
            f.append("[v").append(i).append(']');
        }
        f.append("concat=n=").append(videos.size()).append(":v=1:a=0[video];");

        double total = p.introSec() + p.narrationSec() + p.endCardSec();
        long introMs = Math.round(p.introSec() * 1000);
        f.append(String.format(Locale.ROOT, "[%d:a]aformat=sample_rates=48000:channel_layouts=stereo,"
                + "adelay=%d|%d,apad,atrim=0:%.3f,asplit=2[voice][key];", narrationIn, introMs, introMs, total));
        String audioOut;
        if (p.music().isEmpty()) {
            audioOut = "[voice]";
            f.append("[key]anullsink;");
        } else {
            double fade = 1.5;
            for (int i = 0; i < p.music().size(); i++) {
                MusicSegment m = p.music().get(i);
                double len = m.end() - m.start();
                long delay = Math.round(m.start() * 1000);
                f.append(String.format(Locale.ROOT, "[%d:a]aformat=sample_rates=48000:channel_layouts=stereo,"
                                + "atrim=0:%.3f,asetpts=PTS-STARTPTS,afade=t=in:d=%.2f,afade=t=out:st=%.3f:d=%.2f,"
                                + "adelay=%d|%d[m%d];",
                        firstMusic + i, len, Math.min(fade, len / 3), Math.max(0, len - Math.min(fade, len / 3)),
                        Math.min(fade, len / 3), delay, delay, i));
            }
            for (int i = 0; i < p.music().size(); i++) {
                f.append("[m").append(i).append(']');
            }
            f.append(String.format(Locale.ROOT,
                    "amix=inputs=%d:normalize=0:duration=longest,atrim=0:%.3f,volume=%.1fdB[bed];",
                    p.music().size(), total, p.musicVolumeDb()));
            // Ducking: the narration drives a compressor on the music bed, so music dips whenever someone speaks.
            f.append("[bed][key]sidechaincompress=threshold=0.02:ratio=8:attack=20:release=400[ducked];");
            f.append("[voice][ducked]amix=inputs=2:normalize=0:duration=first[mix];");
            audioOut = "[mix]";
        }
        f.append(audioOut).append("loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000[audio]");

        cmd.addAll(List.of("-filter_complex", f.toString(), "-map", "[video]", "-map", "[audio]",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-r", String.valueOf(v.fps()),
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-t", String.format(Locale.ROOT, "%.3f", total),
                "-movflags", "+faststart", out.toString()));
        run(cmd, 3600);
    }

    public Probe probe(Path file) {
        String info = run(List.of(props.media().ffprobe(), "-v", "error", "-show_entries",
                "stream=codec_type,codec_name,width,height,r_frame_rate:format=duration", "-of",
                "default=noprint_wrappers=1", file.toString()), 120);
        Matcher codecs = Pattern.compile("codec_name=(\\w+)\\s+codec_type=(\\w+)").matcher(info);
        String video = null;
        String audio = null;
        while (codecs.find()) {
            if ("video".equals(codecs.group(2))) {
                video = codecs.group(1);
            } else if ("audio".equals(codecs.group(2))) {
                audio = codecs.group(1);
            }
        }
        Matcher r = Pattern.compile("r_frame_rate=(\\d+)/(\\d+)").matcher(info);
        double fps = r.find() ? Double.parseDouble(r.group(1)) / Double.parseDouble(r.group(2)) : 0;
        return new Probe(num(info, "duration"), (int) num(info, "width"), (int) num(info, "height"), fps, video, audio);
    }

    /** Integrated loudness (LUFS) of the whole file. */
    public double loudness(Path file) {
        String out = run(List.of(props.media().ffmpeg(), "-hide_banner", "-nostats", "-i", file.toString(),
                "-filter_complex", "ebur128", "-f", "null", "-"), 600);
        Matcher m = Pattern.compile("I:\\s+(-?[0-9.]+) LUFS").matcher(out);
        double last = Double.NaN;
        while (m.find()) {
            last = Double.parseDouble(m.group(1));
        }
        return last;
    }

    /** Start times (s) of black stretches of at least 0.5 s. */
    public List<Double> blackStarts(Path file) {
        String out = run(List.of(props.media().ffmpeg(), "-hide_banner", "-i", file.toString(), "-vf",
                "blackdetect=d=0.5:pix_th=0.05", "-an", "-f", "null", "-"), 900);
        List<Double> starts = new ArrayList<>();
        Matcher m = Pattern.compile("black_start:([0-9.]+)").matcher(out);
        while (m.find()) {
            starts.add(Double.parseDouble(m.group(1)));
        }
        return starts;
    }

    /** Length of any media file (seconds), for imported music and brand clips. */
    public double duration(Path file) {
        return num(run(List.of(props.media().ffprobe(), "-v", "error", "-show_entries", "format=duration", "-of",
                "default=noprint_wrappers=1", file.toString()), 60), "duration");
    }

    private static double num(String text, String key) {
        Matcher m = Pattern.compile(key + "=([0-9.]+)").matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private static String run(List<String> cmd, int timeoutSeconds) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssemblyException(cmd.get(0) + " timed out");
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new AssemblyException(cmd.get(0) + " failed: " + text.substring(Math.max(0, text.length() - 600)));
            }
            return text;
        } catch (IOException e) {
            throw new AssemblyException("could not run " + cmd.get(0) + " (is FFmpeg installed?): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssemblyException("interrupted");
        }
    }

    public static class AssemblyException extends RuntimeException {
        public AssemblyException(String message) {
            super(message);
        }
    }
}

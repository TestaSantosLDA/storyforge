package com.storyforge.vertical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.storyforge.config.StoryforgeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class VerticalLayerTest {

    static StoryforgeProperties props(Map<String, String> overrides) {
        Map<String, String> m = new HashMap<>(Map.of("storyforge.paths.assets", "x", "storyforge.paths.characters", "x",
                "storyforge.paths.prompts", "x"));
        m.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(m)).bind("storyforge", StoryforgeProperties.class).get();
    }

    static List<TimedWord> words(String text) {
        String[] ws = text.split(" ");
        return java.util.stream.IntStream.range(0, ws.length)
                .mapToObj(i -> new TimedWord(ws[i], i * 0.4, i * 0.4 + 0.3)).toList();
    }

    final StoryforgeProperties props = props(Map.of());
    final WordCaptions captions = new WordCaptions(props);
    final VerticalMix mix = new VerticalMix(props);
    final VerticalQa qa = new VerticalQa(props);

    @Test
    void defaults_are_the_owners_choices() {
        var v = props.vertical();
        assertThat(v.width()).isEqualTo(1080);
        assertThat(v.height()).isEqualTo(1920);
        assertThat(v.maxDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(v.mix().music()).isTrue();
        assertThat(v.mix().musicDb()).isEqualTo(-17);
        assertThat(v.mix().targetLufs()).isEqualTo(-27);
        assertThat(v.captions().maxWords()).isEqualTo(3);
    }

    @Test
    void groups_are_one_to_three_words_and_end_at_punctuation() {
        var groups = captions.groups(words("The Sun sits still. Everything else moves around it, slowly!"));
        assertThat(groups).extracting(g -> String.join(" ", g.stream().map(TimedWord::text).toList()))
                .containsExactly("The Sun sits", "still.", "Everything else moves", "around it,", "slowly!");
    }

    @Test
    void every_word_gets_its_turn_highlighted_and_captions_never_overlap() {
        String ass = captions.ass(words("Bacchus was jealous of Portugal."));
        List<String> events = ass.lines().filter(l -> l.startsWith("Dialogue:")).toList();
        assertThat(events).hasSize(5);
        assertThat(events.get(0)).contains("{\\c&H003FD2FF&}BACCHUS{\\c&H00FFFFFF&} WAS JEALOUS");
        assertThat(events.get(2)).contains("BACCHUS WAS {\\c&H003FD2FF&}JEALOUS");
        assertThat(events.get(4)).endsWith("{\\c&H003FD2FF&}PORTUGAL{\\c&H00FFFFFF&}");
        // a word stays up until the next one starts
        assertThat(events.get(0)).startsWith("Dialogue: 0,0:00:00.00,0:00:00.40,");
        assertThat(ass).contains("PlayResX: 1080\nPlayResY: 1920");
    }

    @Test
    void transcript_text_cannot_inject_ass_overrides() {
        String ass = captions.ass(List.of(new TimedWord("{\\pos(0,0)}hi", 0, 1)));
        assertThat(ass).doesNotContain("{\\pos");
    }

    @Test
    void colours_are_converted_to_ass_order() {
        assertThat(WordCaptions.colour("#FFD23F")).isEqualTo("&H003FD2FF&");
        assertThatThrownBy(() -> WordCaptions.colour("yellow")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void music_sits_under_the_voice_is_ducked_and_the_mix_is_normalised() {
        String f = mix.filter(0, 1, 40);
        assertThat(f).contains("volume=-17.0dB", "sidechaincompress", "loudnorm=I=-27.0:TP=-2.0", "afade=t=out:st=38.500")
                .endsWith("[audio]");
    }

    @Test
    void music_can_be_turned_off() {
        assertThat(mix.filter(0, null, 40)).doesNotContain("sidechaincompress").contains("loudnorm=I=-27.0");
        var off = new VerticalMix(props(Map.of("storyforge.vertical.mix.music", "false")));
        assertThat(off.filter(0, 1, 40)).doesNotContain("[1:a]");
    }

    @Test
    void loudness_qa_band_is_around_the_target() {
        assertThat(mix.loudnessOnTarget(-27.4)).isTrue();
        assertThat(mix.loudnessOnTarget(-14)).isFalse();
    }

    @Test
    void a_short_must_be_under_sixty_seconds() {
        assertThat(qa.length(59.9).pass()).isTrue();
        assertThat(qa.length(60.0).pass()).isFalse();
    }

    @Test
    void captions_must_say_what_the_narration_said() {
        var said = List.of("Bacchus", "was", "jealous", "of", "Portugal.");
        assertThat(qa.captionsMatchTranscript(words("Bacchus was jealous of Portugal."), said).pass()).isTrue();
        var wrong = qa.captionsMatchTranscript(words("Bacchus was furious."), said);
        assertThat(wrong.pass()).isFalse();
        assertThat(wrong.feedback()).contains("furious.");
    }

    @Test
    void default_captions_sit_inside_the_safe_zone_and_a_bad_margin_fails() {
        assertThat(qa.captionsInSafeZone().pass()).isTrue();
        var low = new VerticalQa(props(Map.of("storyforge.vertical.captions.bottom-margin", "200")));
        assertThat(low.captionsInSafeZone().pass()).isFalse();
    }

    @Test
    void ffmpeg_burns_the_captions_and_mixes_to_the_target(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpegAvailable(), "FFmpeg not on PATH");
        Files.writeString(dir.resolve("captions.ass"), captions.ass(words("The Sun sits in the middle.")));
        // A stand-in voice (tone bursts) and a music bed.
        ffmpeg(dir, "-f", "lavfi", "-i", "sine=frequency=220:duration=6", "-af",
                "volume='if(lt(mod(t,1.5),1),1,0)':eval=frame", "voice.wav");
        ffmpeg(dir, "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "music.wav");
        var v = props.vertical();
        ffmpeg(dir, "-f", "lavfi", "-i", "color=c=0x0d1730:s=" + v.width() + "x" + v.height() + ":r=" + v.fps() + ":d=6",
                "-i", "voice.wav", "-stream_loop", "-1", "-i", "music.wav",
                "-filter_complex", "[0:v]ass=captions.ass,format=yuv420p[video];" + mix.filter(1, 2, 6),
                "-map", "[video]", "-map", "[audio]", "-t", "6", "-c:v", "libx264", "-preset", "ultrafast",
                "-c:a", "aac", "out.mp4");

        String probe = run(dir, "ffprobe", "-v", "error", "-show_entries", "stream=width,height:format=duration",
                "-of", "default=nw=1", "out.mp4");
        assertThat(probe).contains("width=1080", "height=1920");
        assertThat(qa.length(Double.parseDouble(probe.replaceAll("(?s).*duration=([0-9.]+).*", "$1"))).pass()).isTrue();
        String loud = run(dir, "ffmpeg", "-hide_banner", "-nostats", "-i", "out.mp4", "-filter_complex", "ebur128",
                "-f", "null", "-");
        double lufs = Double.parseDouble(loud.replaceAll("(?s).*I:\\s+(-?[0-9.]+) LUFS.*", "$1"));
        assertThat(mix.loudnessOnTarget(lufs)).as(lufs + " LUFS").isTrue();
    }

    static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ffmpeg(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error"));
        cmd.addAll(List.of(args));
        run(dir, cmd.toArray(String[]::new));
    }

    private static String run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
        return out;
    }
}

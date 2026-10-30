package com.storyforge.vertical;

import com.storyforge.config.StoryforgeProperties;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The audio of a vertical video (docs/pipeline-d D4): narration on top; a music bed, on by default, set
 * {@code musicDb} under the voice, faded in and out and ducked further by the narration (sidechain compression); the
 * whole mix normalised to {@code targetLufs}. Levels come from the owner's listening in the first motion test.
 */
@Component
public class VerticalMix {

    private final StoryforgeProperties props;

    public VerticalMix(StoryforgeProperties props) {
        this.props = props;
    }

    /**
     * An FFmpeg filter graph that ends in {@code [audio]}. The narration is input {@code voiceInput}; the music, if
     * {@code musicInput} is not null, is an input the caller loops ({@code -stream_loop -1}) so it covers the video.
     */
    public String filter(int voiceInput, Integer musicInput, double durationSec) {
        var m = props.vertical().mix();
        StringBuilder f = new StringBuilder();
        f.append(String.format(Locale.ROOT, "[%d:a]aformat=sample_rates=48000:channel_layouts=stereo,apad,atrim=0:%.3f",
                voiceInput, durationSec));
        String out;
        if (musicInput == null || !m.music()) {
            f.append("[mix];");
            out = "[mix]";
        } else {
            double fade = Math.min(m.musicFadeSec(), durationSec / 3);
            f.append(",asplit=2[voice][key];");
            f.append(String.format(Locale.ROOT, "[%d:a]aformat=sample_rates=48000:channel_layouts=stereo,"
                            + "atrim=0:%.3f,asetpts=PTS-STARTPTS,volume=%.1fdB,afade=t=in:d=%.2f,"
                            + "afade=t=out:st=%.3f:d=%.2f[bed];",
                    musicInput, durationSec, m.musicDb(), fade, durationSec - fade, fade));
            // Ducking: the narration drives a compressor on the bed, so the music dips further while anyone speaks.
            f.append("[bed][key]sidechaincompress=threshold=0.02:ratio=6:attack=20:release=500[ducked];");
            f.append("[voice][ducked]amix=inputs=2:normalize=0:duration=first[mix];");
            out = "[mix]";
        }
        f.append(out).append(String.format(Locale.ROOT, "loudnorm=I=%.1f:TP=%.1f:LRA=11,aresample=48000[audio]",
                m.targetLufs(), m.truePeakDb()));
        return f.toString();
    }

    /** Loudness QA band around the target, as Stage 4 allows around its own target. */
    public boolean loudnessOnTarget(double lufs) {
        return Math.abs(lufs - props.vertical().mix().targetLufs()) <= 1.5;
    }
}

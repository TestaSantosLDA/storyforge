package com.storyforge.stage4;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubtitlesTest {

    @Test
    void short_lines_are_one_cue_on_one_row_at_the_manifest_times() {
        var cues = Subtitles.cues(List.of(new Subtitles.Spoken("Oh.", 3.0, 4.1)));
        assertThat(cues).singleElement().satisfies(c -> {
            assertThat(c.rows()).containsExactly("Oh.");
            assertThat(c.start()).isEqualTo(3.0);
            assertThat(c.end()).isEqualTo(4.1);
        });
    }

    @Test
    void long_lines_wrap_to_two_rows_and_very_long_ones_split_into_timed_cues() {
        String text = "It was the longest night of winter in Whisperwood, and the mist lay thick as porridge. "
                + "By old custom every creature set one small light in their doorway.";
        var cues = Subtitles.cues(List.of(new Subtitles.Spoken(text, 10.0, 20.0)));

        assertThat(cues).hasSizeGreaterThan(1);
        assertThat(cues).allSatisfy(c -> {
            assertThat(c.rows()).hasSizeLessThanOrEqualTo(2);
            assertThat(c.rows()).allMatch(r -> r.length() <= Subtitles.MAX_CHARS);
        });
        assertThat(cues.getFirst().start()).isEqualTo(10.0);
        assertThat(cues.getLast().end()).isEqualTo(20.0);
        for (int i = 1; i < cues.size(); i++) {
            assertThat(cues.get(i).start()).isEqualTo(cues.get(i - 1).end());
        }
        String joined = String.join(" ", cues.stream().flatMap(c -> c.rows().stream()).toList());
        assertThat(joined).isEqualTo(text);
    }

    @Test
    void srt_format() {
        String srt = Subtitles.srt(Subtitles.cues(List.of(new Subtitles.Spoken("Hello there.", 61.5, 63.25))));
        assertThat(srt).isEqualTo("1\n00:01:01,500 --> 00:01:03,250\nHello there.\n\n");
    }
}

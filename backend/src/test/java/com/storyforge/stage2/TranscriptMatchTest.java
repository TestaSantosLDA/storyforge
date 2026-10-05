package com.storyforge.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TranscriptMatchTest {

    Set<String> names = Set.of("Tamsin", "Oswin", "Mackerel");

    @Test
    void identical_after_case_and_punctuation_is_a_full_match() {
        assertThat(TranscriptMatch.score("\"That's it?\" said Tamsin.", "that's it said tamsin", names)).isEqualTo(1.0);
    }

    @Test
    void whisper_name_spellings_and_us_spelling_still_match() {
        // Real Whisper output from the 2026-10-05 test: "Tamsen", "Aswin", "harbor".
        assertThat(TranscriptMatch.score(
                "Tamsin, Oswin and Mackerel met Skipper by the harbour, where Wren was waiting with the last warm chip.",
                "Tamsin, Aswin, and Mackerel met Skipper by the harbor, where Wren was waiting with the last warm chip.",
                names)).isEqualTo(1.0);
        assertThat(TranscriptMatch.score("a young fox named Tamsin", "a young fox named Tamsen", names)).isEqualTo(1.0);
    }

    @Test
    void words_that_sound_alike_and_a_swallowed_past_tense_still_match() {
        // Both from the 2026-10-05 harbour run, where correct readings failed three times.
        assertThat(TranscriptMatch.score("I checked the facts. You are right.", "I check the facts, you are right.",
                names)).isEqualTo(1.0);
        assertThat(TranscriptMatch.score("And off they went along the shining quay, because a small thing, shared,"
                + " turns out to be plenty.", "And off they went along the shining key, because a small thing shared"
                + " turns out to be plenty.", names)).isEqualTo(1.0);
        assertThat(TranscriptMatch.score("the tale of the hare", "the tail of the hair", names)).isEqualTo(1.0);
        // A different word is still a miss.
        assertThat(TranscriptMatch.score("she checked the boat", "she chased the boat", names)).isLessThan(1.0);
    }

    @Test
    void numbers_are_normalised() {
        assertThat(TranscriptMatch.score("She counted 3 chips.", "She counted three chips.", names)).isEqualTo(1.0);
    }

    @Test
    void dropped_or_extra_words_cost() {
        assertThat(TranscriptMatch.score("the mist lay thick as porridge", "the mist lay thick", names))
                .isLessThan(0.95);
        assertThat(TranscriptMatch.score("the mist lay thick", "the mist lay thick thick as porridge", names))
                .isLessThan(0.95);
    }

    @Test
    void ordinary_words_are_not_fuzzy_matched() {
        assertThat(TranscriptMatch.score("the cat sat", "the hat sat", names)).isLessThan(0.95);
    }
}

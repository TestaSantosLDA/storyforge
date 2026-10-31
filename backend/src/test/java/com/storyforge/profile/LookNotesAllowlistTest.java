package com.storyforge.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Series C9 and scenario 7: names from the topic's public-domain source pass the existing-character check. */
class LookNotesAllowlistTest {

    final LookNotesCheck check = new LookNotesCheck(List.of("Pixar", "Adamastor"));

    @Test
    void s7_an_allowlisted_public_domain_name_is_let_through() {
        assertThat(check.findBanned("Adamastor towers over the cape")).contains("Adamastor");
        assertThat(check.findBanned("Adamastor towers over the cape", List.of("adamastor"))).isEmpty();
    }

    @Test
    void the_allowlist_never_lets_a_studio_through_that_it_does_not_name() {
        assertThat(check.findBanned("Adamastor, but make it Pixar", List.of("Adamastor"))).contains("Pixar");
    }

    @Test
    void the_allowlist_round_trips_as_json() {
        assertThat(LookNotesCheck.allowlist(LookNotesCheck.allowlistJson(List.of("Venus", "Inês de Castro"))))
                .containsExactly("Venus", "Inês de Castro");
        assertThat(LookNotesCheck.allowlist(null)).isEmpty();
    }
}

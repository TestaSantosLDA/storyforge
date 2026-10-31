package com.storyforge.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.character.CharacterFile.DrawnBy;
import com.storyforge.character.CharacterFile.Kind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;

/** Code-drawn characters (series C10) and long-dead historical figures (C3). */
class CharacterRulesTest {

    static CharacterFile camoes(DrawnBy drawnBy, String component, Boolean historical, Integer died) {
        return new CharacterFile("camoes", "Camões", 7L, Kind.RECURRING, "Witty host", "One eye, laurel wreath",
                null, null, null, drawnBy, component, List.of("point", "shrug", "facepalm", "wink"), historical, died);
    }

    @Test
    void camoes_as_a_code_drawn_historical_host_is_valid_and_round_trips_through_yaml() throws Exception {
        Path dir = Path.of("target/test-characters-rules");
        FileSystemUtils.deleteRecursively(dir);
        CharacterStore store = new CharacterStore(dir, 1900);
        store.create(camoes(DrawnBy.CODE, "camoes", true, 1580));
        CharacterFile back = store.get("camoes").orElseThrow();
        assertThat(back.drawing()).isEqualTo(DrawnBy.CODE);
        assertThat(back.kitComponent()).isEqualTo("camoes");
        assertThat(back.poses()).contains("wink");
        assertThat(back.historicalFigure()).isTrue();
        assertThat(back.died()).isEqualTo(1580);
    }

    @Test
    void old_files_without_the_new_fields_are_flux_characters() {
        CharacterFile fox = new CharacterFile("tamsin", "Tamsin", 1L, Kind.RECURRING, "p", "v", List.of("a.png"), null, 3L);
        assertThat(fox.drawing()).isEqualTo(DrawnBy.FLUX);
        assertThat(fox.problems(1900)).isEmpty();
    }

    @Test
    void a_code_character_needs_its_kit_component_and_no_reference_images() {
        assertThat(camoes(DrawnBy.CODE, null, null, null).problems(1900))
                .containsExactly("Camões is drawn by code but names no kit component.");
        CharacterFile withRefs = new CharacterFile("x", "X", 1L, Kind.ONE_OFF, "p", "v", List.of("a.png"), null, null,
                DrawnBy.CODE, "x", null, null, null);
        assertThat(withRefs.problems(1900)).singleElement().asString().contains("pose sheet");
    }

    @Test
    void c3_real_people_must_have_died_before_1900_and_be_drawn_only_by_code() {
        assertThat(camoes(DrawnBy.CODE, "camoes", true, null).problems(1900)).singleElement().asString()
                .contains("no year of death");
        assertThat(camoes(DrawnBy.CODE, "camoes", true, 1950).problems(1900)).singleElement().asString()
                .contains("died before 1900");
        assertThat(camoes(DrawnBy.FLUX, null, true, 1580).problems(1900)).singleElement().asString()
                .contains("only be drawn by kit code");
        assertThatThrownBy(() -> new CharacterStore(Path.of("target/test-characters-rules2"), 1900)
                .create(camoes(DrawnBy.FLUX, null, true, 1580))).isInstanceOf(IllegalArgumentException.class);
    }
}

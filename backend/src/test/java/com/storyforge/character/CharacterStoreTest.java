package com.storyforge.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CharacterStoreTest {

    @TempDir
    Path dir;

    CharacterFile bramble = new CharacterFile("bramble", "Bramble", 1L, CharacterFile.Kind.RECURRING,
            "Curious young fox", "Small red fox with a dusty-blue striped scarf",
            List.of("characters/bramble/front.png"), Map.of("engine", "kokoro", "id", "am_puck"), 7L);

    @Test
    void round_trips_as_snake_case_yaml() throws Exception {
        var store = new CharacterStore(dir);
        store.create(bramble);

        String yaml = Files.readString(dir.resolve("bramble.yaml"));
        assertThat(yaml).contains("topic_id: 1", "kind: \"recurring\"", "visual_description:");
        assertThat(store.get("bramble")).contains(bramble);
        assertThat(store.byTopic(1)).containsExactly(bramble);
        assertThat(store.byTopic(2)).isEmpty();
    }

    @Test
    void ids_and_names_are_unique_across_the_channel() {
        var store = new CharacterStore(dir);
        store.create(bramble);

        assertThatThrownBy(() -> store.create(bramble)).hasMessageContaining("id already exists");
        var sameName = new CharacterFile("bramble-2", "bramble", 2L, CharacterFile.Kind.ONE_OFF,
                "p", "v", List.of(), Map.of(), 9L);
        assertThatThrownBy(() -> store.create(sameName)).hasMessageContaining("name already exists");
    }

    @Test
    void reads_a_hand_written_file() throws Exception {
        Files.writeString(dir.resolve("hazel.yaml"), """
                id: hazel
                name: Hazel
                topic_id: 1
                kind: one_off
                personality: Patient old owl
                visual_description: Plump tawny owl with round wire spectacles
                reference_images: []
                """);
        var hazel = new CharacterStore(dir).get("hazel").orElseThrow();
        assertThat(hazel.kind()).isEqualTo(CharacterFile.Kind.ONE_OFF);
        assertThat(hazel.voice()).isNull();
    }
}

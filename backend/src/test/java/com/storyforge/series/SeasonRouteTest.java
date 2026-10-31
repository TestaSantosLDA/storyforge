package com.storyforge.series;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Scenario 11: the route map's state comes from series/lusiadas/route.yaml up to the episode number. */
class SeasonRouteTest {

    final SeasonRoute routes = new SeasonRoute(Path.of("../series"));
    final SeasonRoute.Route route = routes.find("lusiadas").orElseThrow();

    @Test
    void s11_episode_5_never_shows_calicut() {
        List<Map<String, Object>> ep5 = routes.forEpisode(route, 5);
        assertThat(ep5).extracting(m -> m.get("id")).doesNotContain("calicut", "island-of-love");
        assertThat(ep5).allSatisfy(m -> assertThat(m.get("reached")).isEqualTo(true));
    }

    @Test
    void earlier_stops_are_reached_and_this_episodes_stops_animate_in() {
        List<Map<String, Object>> ep2 = routes.forEpisode(route, 2);
        assertThat(ep2).extracting(m -> m.get("id")).containsExactly("lisbon", "cape-verde", "st-helena-bay",
                "cape-of-good-hope", "mozambique", "mombasa", "malindi");
        assertThat(ep2).filteredOn(m -> m.get("reached").equals(false)).extracting(m -> m.get("id"))
                .containsExactly("mombasa", "malindi");
    }

    @Test
    void stops_are_projected_into_the_map_box_with_years_and_kinds() {
        List<Map<String, Object>> ep11 = routes.forEpisode(route, 11);
        assertThat(ep11).allSatisfy(m -> {
            assertThat((Double) m.get("x")).isBetween(0.0, 1.0);
            assertThat((Double) m.get("y")).isBetween(0.0, 1.0);
        });
        Map<String, Object> lisbon = ep11.getFirst();
        assertThat(lisbon).containsEntry("x", 0.19).containsEntry("year", "1497").containsEntry("kind", "documented");
        assertThat(ep11).filteredOn(m -> m.get("id").equals("island-of-love")).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("kind", "legend").doesNotContainKey("year"));
    }
}

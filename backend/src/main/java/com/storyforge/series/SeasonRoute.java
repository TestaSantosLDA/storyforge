package com.storyforge.series;

import com.storyforge.config.StoryforgeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The season route map's state (docs/series/lusiadas.md "Scene kit additions"), read from the versioned file
 * {@code series/<id>/route.yaml}, never from Claude: each stop names the episode that first shows it, so an episode
 * draws the stops of earlier episodes as already reached and animates its own (scenario 11).
 */
@Component
public class SeasonRoute {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** Map extent in degrees; stops are placed by an equirectangular projection into 0..1. */
    public record Extent(double west, double east, double north, double south) {
    }

    public record Stop(String id, String name, double lat, double lon, String date, int episode, String kind) {
    }

    public record Route(String series, Extent extent, List<Stop> stops) {
    }

    private final Path dir;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Autowired
    SeasonRoute(StoryforgeProperties props) {
        this(props.paths().seriesDir());
    }

    public SeasonRoute(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Optional<Route> find(String seriesId) {
        if (seriesId == null || !ID.matcher(seriesId).matches()) {
            return Optional.empty();
        }
        Path p = dir.resolve(seriesId).resolve("route.yaml");
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        Route r = yaml.readValue(p.toFile(), Route.class);
        for (int i = 1; i < r.stops().size(); i++) {
            if (r.stops().get(i).episode() < r.stops().get(i - 1).episode()) {
                throw new IllegalStateException(p + ": stop " + r.stops().get(i).id() + " comes before an earlier "
                        + "episode's stop; list stops in voyage order with non-decreasing episodes");
            }
        }
        return Optional.of(r);
    }

    /**
     * The kit's {@code routeMap} input for an episode: stops up to and including this episode, with x and y in 0..1.
     * Stops from earlier episodes carry {@code reached: true} (drawn from the start, no {@code t}); this episode's
     * stops carry {@code reached: false}, and the scene gives them their times. Later stops are left out.
     */
    public List<Map<String, Object>> forEpisode(Route route, int episode) {
        Extent e = route.extent();
        return route.stops().stream().filter(s -> s.episode() <= episode).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("name", s.name());
            m.put("x", round((s.lon() - e.west()) / (e.east() - e.west())));
            m.put("y", round((e.north() - s.lat()) / (e.north() - e.south())));
            if (s.date() != null) {
                m.put("year", s.date().substring(0, 4));
            }
            m.put("kind", s.kind() == null ? "documented" : s.kind());
            m.put("reached", s.episode() < episode);
            return m;
        }).toList();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}

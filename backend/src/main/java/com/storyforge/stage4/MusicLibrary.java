package com.storyforge.stage4;

import com.storyforge.storage.AssetStorage;
import com.storyforge.validation.InvalidInputException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Array;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The curated music library (Stage 4). A human imports each track with its license file; non-commercial licenses
 * are rejected at import. The pipeline never downloads music itself.
 */
@Component
public class MusicLibrary {

    /** Scene moods from the Stage 1 scene breakdown. */
    public static final Set<String> MOODS = Set.of("calm", "playful", "tense_but_gentle", "happy_ending");

    /** License types allowed for commercial use on YouTube, TikTok and Instagram. Anything else is refused. */
    public static final Map<String, String> LICENSES = new LinkedHashMap<>();

    static {
        LICENSES.put("pixabay", "Pixabay Content License");
        LICENSES.put("mixkit", "Mixkit Free License");
        LICENSES.put("cc0", "CC0 (public domain)");
        LICENSES.put("cc_by", "CC BY (attribution required)");
    }

    public record Track(long id, String title, String assetKey, double durationSec, List<String> moods, String sourceUrl,
            String licenseType, String licenseKey, String attribution) {
    }

    private final JdbcClient jdbc;
    private final AssetStorage assets;

    MusicLibrary(JdbcClient jdbc, AssetStorage assets) {
        this.jdbc = jdbc;
        this.assets = assets;
    }

    public Track importTrack(String title, byte[] audio, String audioName, double durationSec, List<String> moods,
            String sourceUrl, String licenseType, byte[] licenseFile, String licenseName, String attribution) {
        String type = licenseType == null ? "" : licenseType.toLowerCase(Locale.ROOT).strip();
        Map<String, String> errors = new LinkedHashMap<>();
        if (title == null || title.isBlank()) {
            errors.put("title", "Give the track a title.");
        }
        if (audio == null || audio.length == 0) {
            errors.put("audio", "Choose the music file.");
        }
        if (type.contains("nc") || type.contains("noncommercial") || type.contains("non-commercial")) {
            errors.put("license", "Non-commercial licenses can't be used on a monetized channel.");
        } else if (!LICENSES.containsKey(type)) {
            errors.put("license", "Pick one of the allowed licenses.");
        }
        if ("cc_by".equals(type) && (attribution == null || attribution.isBlank())) {
            errors.put("attribution", "CC BY needs the attribution text.");
        }
        if (licenseFile == null || licenseFile.length == 0) {
            errors.put("licenseFile", "Attach the license file (proof for copyright disputes).");
        }
        if (sourceUrl == null || !sourceUrl.startsWith("http")) {
            errors.put("sourceUrl", "Give the page the track came from.");
        }
        if (moods == null || moods.isEmpty() || !MOODS.containsAll(moods)) {
            errors.put("moods", "Tag at least one mood.");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String stamp = String.valueOf(System.currentTimeMillis());
        String audioKey = "music/" + slug + "-" + stamp + ext(audioName, ".mp3");
        String licenseKey = "music/licenses/" + slug + "-" + stamp + ext(licenseName, ".txt");
        try {
            assets.put(audioKey, new ByteArrayInputStream(audio));
            assets.put(licenseKey, new ByteArrayInputStream(licenseFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long id = jdbc.sql("""
                insert into music_track (title, asset_key, duration_sec, moods, source_url, license_type, license_key,
                    attribution) values (:t, :k, :d, cast(:m as text[]), :u, :l, :lk, :a) returning id
                """)
                .param("t", title.strip()).param("k", audioKey).param("d", BigDecimal.valueOf(durationSec))
                .param("m", "{" + String.join(",", moods) + "}").param("u", sourceUrl.strip()).param("l", type)
                .param("lk", licenseKey).param("a", attribution == null || attribution.isBlank() ? null : attribution.strip())
                .query(Long.class).single();
        return get(id);
    }

    public Track get(long id) {
        return jdbc.sql("select * from music_track where id = :id").param("id", id).query(this::map).single();
    }

    public List<Track> all() {
        return jdbc.sql("select * from music_track order by title").query(this::map).list();
    }

    /** A track may be used only with a license record that allows commercial, cross-platform use (QA "Music licenses"). */
    public boolean licensed(Track t) {
        return LICENSES.containsKey(t.licenseType()) && t.licenseKey() != null && assets.exists(t.licenseKey())
                && (!"cc_by".equals(t.licenseType()) || t.attribution() != null);
    }

    private static String ext(String name, String fallback) {
        if (name == null || !name.contains(".")) {
            return fallback;
        }
        String e = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        return e.matches("\\.[a-z0-9]{2,5}") ? e : fallback;
    }

    private Track map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Array moods = rs.getArray("moods");
        return new Track(rs.getLong("id"), rs.getString("title"), rs.getString("asset_key"),
                rs.getBigDecimal("duration_sec").doubleValue(), Arrays.asList((String[]) moods.getArray()),
                rs.getString("source_url"), rs.getString("license_type"), rs.getString("license_key"),
                rs.getString("attribution"));
    }
}

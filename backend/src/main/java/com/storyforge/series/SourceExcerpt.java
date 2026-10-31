package com.storyforge.series;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stanzas an episode is grounded in (series C5): Canto plus stanza ranges, stored as
 * {@code story.source_excerpt} = {@code [{canto, from_stanza, to_stanza}, ...]}. Typed on the story form as
 * {@code "I 1-41"} or {@code "I 1-18; II 3-5"} (Roman or Arabic canto numbers).
 */
public record SourceExcerpt(List<Range> ranges) {

    public record Range(int canto, @JsonProperty("from_stanza") int fromStanza,
            @JsonProperty("to_stanza") int toStanza) {

        public String label() {
            return "Canto " + roman(canto) + ", st. " + (fromStanza == toStanza ? fromStanza : fromStanza + "–" + toStanza);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ITEM = Pattern.compile("(?i)\\s*(?:canto\\s+)?([IVXL]+|\\d+)\\s*[,:]?\\s*(?:st\\.?|stanzas?)?\\s*(\\d+)\\s*(?:[-–]\\s*(\\d+))?\\s*");
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII",
        "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};

    /**
     * Parses the typed form and checks every range against the source's stanza counts. Throws
     * {@link IllegalArgumentException} with a message fit for the form.
     */
    public static SourceExcerpt parse(String text, SourceTexts.Source source) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Give the stanzas this episode covers, e.g. \"I 19-41\".");
        }
        List<Range> ranges = new ArrayList<>();
        for (String part : text.split(";")) {
            if (part.isBlank()) {
                continue;
            }
            Matcher m = ITEM.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("Can't read \"" + part.strip() + "\". Write a canto and stanzas, e.g. \"I 19-41\".");
            }
            int canto = cantoNumber(m.group(1));
            int from = Integer.parseInt(m.group(2));
            int to = m.group(3) == null ? from : Integer.parseInt(m.group(3));
            int cantos = source.stanzaCounts().size();
            if (canto < 1 || canto > cantos) {
                throw new IllegalArgumentException(source.title() + " has cantos I to " + roman(cantos) + ".");
            }
            int max = source.stanzaCounts().get(canto - 1);
            if (from < 1 || to < from || to > max) {
                throw new IllegalArgumentException("Canto " + roman(canto) + " has stanzas 1 to " + max + ".");
            }
            ranges.add(new Range(canto, from, to));
        }
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("Give the stanzas this episode covers, e.g. \"I 19-41\".");
        }
        return new SourceExcerpt(List.copyOf(ranges));
    }

    public static SourceExcerpt fromJson(String json) {
        return json == null ? null : new SourceExcerpt(JSON.readValue(json, new TypeReference<List<Range>>() { }));
    }

    public String toJson() {
        return JSON.writeValueAsString(ranges);
    }

    /** The typed form again, e.g. {@code "I 1-18; II 3-5"}. */
    public String asText() {
        return ranges.stream().map(r -> roman(r.canto()) + " " + r.fromStanza()
                + (r.toStanza() == r.fromStanza() ? "" : "-" + r.toStanza())).collect(Collectors.joining("; "));
    }

    public String label() {
        return ranges.stream().map(Range::label).collect(Collectors.joining("; "));
    }

    public List<Map<String, Integer>> asMaps() {
        return ranges.stream().map(r -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("canto", r.canto());
            m.put("from_stanza", r.fromStanza());
            m.put("to_stanza", r.toStanza());
            return m;
        }).toList();
    }

    static String roman(int n) {
        return n > 0 && n < ROMAN.length ? ROMAN[n] : String.valueOf(n);
    }

    private static int cantoNumber(String s) {
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(s);
        }
        String up = s.toUpperCase(Locale.ROOT);
        for (int i = 1; i < ROMAN.length; i++) {
            if (ROMAN[i].equals(up)) {
                return i;
            }
        }
        return -1;
    }
}

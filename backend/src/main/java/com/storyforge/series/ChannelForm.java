package com.storyforge.series;

import com.storyforge.validation.InvalidInputException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Fields a person enters for a channel (C2). Music moods are entered comma-separated. */
public record ChannelForm(String name, String youtubeHandle, String tiktokAccount, String instagramAccount,
        String narratorVoice, String closingLineText, Boolean madeForKids, String musicMoods) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static ChannelForm of(Channel c) {
        return new ChannelForm(c.getName(), c.getYoutubeHandle(), c.getTiktokAccount(), c.getInstagramAccount(),
                c.getNarratorVoice(), c.getClosingLineText(), c.isMadeForKids(),
                String.join(", ", moodsFromJson(c.getMusicMoods())));
    }

    public static ChannelForm blank() {
        return new ChannelForm("", "", "", "", "", "", false, "");
    }

    /** Throws with every field problem at once; returns the form with blanks turned into nulls. */
    public ChannelForm validated() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (blank(name)) {
            errors.put("name", "Give the channel a name.");
        }
        if (!blank(youtubeHandle) && !youtubeHandle.strip().matches("@[A-Za-z0-9._-]{3,30}")) {
            errors.put("youtubeHandle", "A YouTube handle looks like @name (3 to 30 letters, digits, dots, dashes).");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return new ChannelForm(name.strip(), clean(youtubeHandle), clean(tiktokAccount), clean(instagramAccount),
                clean(narratorVoice), clean(closingLineText), Boolean.TRUE.equals(madeForKids), musicMoods);
    }

    List<String> moods() {
        if (blank(musicMoods)) {
            return List.of();
        }
        return Arrays.stream(musicMoods.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    String musicMoodsJson() {
        List<String> m = moods();
        return m.isEmpty() ? null : JSON.writeValueAsString(m);
    }

    static List<String> moodsFromJson(String json) {
        return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<String>>() { });
    }

    private static String clean(String s) {
        return blank(s) ? null : s.strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

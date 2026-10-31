package com.storyforge.series;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A publishing channel (docs/series/lusiadas.md C2). Every topic belongs to one; the fables channel is the default.
 * A story captures its topic's channel at Run, so later edits don't change where a running story is published.
 */
@Entity
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private boolean isDefault;
    private String youtubeHandle;
    private String tiktokAccount;
    private String instagramAccount;
    private String narratorVoice;
    private String watermarkKey;
    private String titleStingKey;
    private String closingLineText;
    private String closingLineAudioKey;
    private boolean madeForKids;

    @JdbcTypeCode(SqlTypes.JSON)
    private String musicMoods;

    private Instant createdAt;
    private Instant updatedAt;

    protected Channel() {
    }

    public Channel(String name) {
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Sets every editable field at once (the channel form). Asset keys are set by the asset upload, not here. */
    void edit(ChannelForm f) {
        this.name = f.name();
        this.youtubeHandle = f.youtubeHandle();
        this.tiktokAccount = f.tiktokAccount();
        this.instagramAccount = f.instagramAccount();
        this.narratorVoice = f.narratorVoice();
        this.closingLineText = f.closingLineText();
        this.madeForKids = Boolean.TRUE.equals(f.madeForKids());
        this.musicMoods = f.musicMoodsJson();
    }

    /** What a story captures at Run and what the change log records. */
    public Map<String, Object> details() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("youtube_handle", youtubeHandle);
        m.put("tiktok_account", tiktokAccount);
        m.put("instagram_account", instagramAccount);
        m.put("narrator_voice", narratorVoice);
        m.put("watermark_key", watermarkKey);
        m.put("title_sting_key", titleStingKey);
        m.put("closing_line_text", closingLineText);
        m.put("closing_line_audio_key", closingLineAudioKey);
        m.put("made_for_kids", madeForKids);
        m.put("music_moods", ChannelForm.moodsFromJson(musicMoods));
        return m;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public String getYoutubeHandle() {
        return youtubeHandle;
    }

    public String getTiktokAccount() {
        return tiktokAccount;
    }

    public String getInstagramAccount() {
        return instagramAccount;
    }

    public String getNarratorVoice() {
        return narratorVoice;
    }

    public String getWatermarkKey() {
        return watermarkKey;
    }

    public String getTitleStingKey() {
        return titleStingKey;
    }

    public String getClosingLineText() {
        return closingLineText;
    }

    public String getClosingLineAudioKey() {
        return closingLineAudioKey;
    }

    public boolean isMadeForKids() {
        return madeForKids;
    }

    public String getMusicMoods() {
        return musicMoods;
    }
}

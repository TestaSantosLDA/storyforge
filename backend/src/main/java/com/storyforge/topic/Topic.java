package com.storyforge.topic;

import com.storyforge.series.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A universe (docs/pipeline-a/stage-0 "Topic"). */
@Entity
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String description;

    /** Pipeline D video profile file id; null for a fable topic. */
    private String profileId;

    /** Overrides of profile fields as JSON, snake_case keys as in the profile file. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String profileOverrides;

    private String lookNotes;

    /** Where the topic's videos are published (series C2). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    /** Episodes with a season and number (series C5). */
    private boolean isSeries;

    /** Folder under sources/ with the topic's public-domain source text, or null. */
    private String sourceId;

    /** Names from the public-domain source allowed in prompts, scripts and look notes (C9), as a JSON list. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String nameAllowlist;

    private Instant createdAt;
    private Instant updatedAt;

    protected Topic() {
    }

    public Topic(String name, String description, Channel channel) {
        this.name = name;
        this.description = description;
        this.channel = channel;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getProfileOverrides() {
        return profileOverrides;
    }

    public String getLookNotes() {
        return lookNotes;
    }

    public void setProfile(String profileId, String profileOverrides, String lookNotes) {
        this.profileId = profileId;
        this.profileOverrides = profileOverrides;
        this.lookNotes = lookNotes;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public boolean isSeries() {
        return isSeries;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getNameAllowlist() {
        return nameAllowlist;
    }

    public void setSeries(boolean isSeries, String sourceId, String nameAllowlist) {
        this.isSeries = isSeries;
        this.sourceId = sourceId;
        this.nameAllowlist = nameAllowlist;
    }
}

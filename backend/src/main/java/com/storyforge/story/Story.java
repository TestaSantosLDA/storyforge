package com.storyforge.story;

import com.storyforge.topic.Topic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A story row (docs/pipeline-a/stage-0 "Story"). The status is changed only through {@link StoryStatusService},
 * so every change is checked against {@link StoryTransitions} and logged.
 */
@Entity
public class Story {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id")
    private Topic topic;

    @Column(nullable = false)
    private String concept;

    @Column(nullable = false)
    private String moral;

    private int targetLengthMin = 4;
    private String notes;
    private Integer queuePosition;

    @Column(nullable = false)
    private StoryStatus status;

    @Column(nullable = false)
    private StoryFormat format = StoryFormat.FABLE;

    private StoryStatus resumeStatus;
    private String attentionReason;

    @Enumerated(EnumType.STRING)
    private ArchiveReason archiveReason;

    private String topicSnapshot;

    private String lookNotes;

    /** Profile, overrides and look notes captured at Run (Pipeline D); null for a fable. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String profileSnapshot;

    /** Series episode fields (docs/series/lusiadas.md C5); null for a story outside a series. */
    private Integer season;
    private Integer episodeNumber;

    /** JSON list of {canto, from_stanza, to_stanza}; see {@link com.storyforge.series.SourceExcerpt}. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String sourceExcerpt;

    private boolean recapEnabled;

    /** The topic's channel captured at Run (C2). */
    @JdbcTypeCode(SqlTypes.JSON)
    private String channelSnapshot;

    private Instant autoResumeAt;

    private int storyQaRetries;
    private int storyRejections;
    private int scriptQaRetries;
    private int scriptRejections;
    private int finalRejections;

    private BigDecimal costUsd = BigDecimal.ZERO;

    @Version
    private long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected Story() {
    }

    public Story(Topic topic, String concept, String moral, int targetLengthMin, String notes, int queuePosition) {
        this(topic, StoryFormat.FABLE, concept, moral, targetLengthMin, notes, queuePosition);
    }

    public Story(Topic topic, StoryFormat format, String concept, String moral, int targetLengthMin, String notes,
            int queuePosition) {
        this.format = format;
        this.topic = topic;
        this.concept = concept;
        this.moral = moral;
        this.targetLengthMin = targetLengthMin;
        this.notes = notes;
        this.queuePosition = queuePosition;
        this.status = StoryStatus.QUEUED;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // Package-private mutators: only the status service changes pipeline position.

    void setStatus(StoryStatus status) {
        this.status = status;
    }

    void setQueuePosition(Integer queuePosition) {
        this.queuePosition = queuePosition;
    }

    void setResumeStatus(StoryStatus resumeStatus) {
        this.resumeStatus = resumeStatus;
    }

    void setAttentionReason(String attentionReason) {
        this.attentionReason = attentionReason;
    }

    void setArchiveReason(ArchiveReason archiveReason) {
        this.archiveReason = archiveReason;
    }

    void setAutoResumeAt(Instant autoResumeAt) {
        this.autoResumeAt = autoResumeAt;
    }

    void setTopicSnapshot(String topicSnapshot) {
        this.topicSnapshot = topicSnapshot;
    }

    void setProfileSnapshot(String profileSnapshot) {
        this.profileSnapshot = profileSnapshot;
    }

    void setEpisode(Integer season, Integer episodeNumber, String sourceExcerpt, boolean recapEnabled) {
        this.season = season;
        this.episodeNumber = episodeNumber;
        this.sourceExcerpt = sourceExcerpt;
        this.recapEnabled = recapEnabled;
    }

    void setChannelSnapshot(String channelSnapshot) {
        this.channelSnapshot = channelSnapshot;
    }

    void setLookNotes(String lookNotes) {
        this.lookNotes = lookNotes;
    }

    void updateDetails(String concept, String moral, int targetLengthMin, String notes) {
        this.concept = concept;
        this.moral = moral;
        this.targetLengthMin = targetLengthMin;
        this.notes = notes;
    }

    /** Field values for the change log. */
    public Map<String, Object> details() {
        var m = new LinkedHashMap<String, Object>();
        m.put("topic_id", topic.getId());
        m.put("concept", concept);
        m.put("moral", moral);
        m.put("target_length_min", targetLengthMin);
        m.put("notes", notes);
        m.put("look_notes", lookNotes);
        m.put("season", season);
        m.put("episode_number", episodeNumber);
        m.put("source_excerpt", sourceExcerpt);
        m.put("recap_enabled", recapEnabled);
        m.put("queue_position", queuePosition);
        m.put("format", format.dbValue());
        m.put("status", status.dbValue());
        return m;
    }

    int increment(StoryCounter c) {
        return switch (c) {
            case STORY_QA_RETRIES -> ++storyQaRetries;
            case STORY_REJECTIONS -> ++storyRejections;
            case SCRIPT_QA_RETRIES -> ++scriptQaRetries;
            case SCRIPT_REJECTIONS -> ++scriptRejections;
            case FINAL_REJECTIONS -> ++finalRejections;
        };
    }

    void resetCounters() {
        storyQaRetries = storyRejections = scriptQaRetries = scriptRejections = finalRejections = 0;
    }

    void addCost(BigDecimal usd) {
        costUsd = costUsd.add(usd);
    }

    public Long getId() {
        return id;
    }

    public Topic getTopic() {
        return topic;
    }

    public String getConcept() {
        return concept;
    }

    public String getMoral() {
        return moral;
    }

    public int getTargetLengthMin() {
        return targetLengthMin;
    }

    public String getNotes() {
        return notes;
    }

    public Integer getQueuePosition() {
        return queuePosition;
    }

    public StoryFormat getFormat() {
        return format;
    }

    public StoryStatus getStatus() {
        return status;
    }

    public StoryStatus getResumeStatus() {
        return resumeStatus;
    }

    public String getAttentionReason() {
        return attentionReason;
    }

    public ArchiveReason getArchiveReason() {
        return archiveReason;
    }

    public String getTopicSnapshot() {
        return topicSnapshot;
    }

    public String getLookNotes() {
        return lookNotes;
    }

    public String getProfileSnapshot() {
        return profileSnapshot;
    }

    public Integer getSeason() {
        return season;
    }

    public Integer getEpisodeNumber() {
        return episodeNumber;
    }

    /** "S1 · Ep.03" for a series episode, else null. */
    public String getEpisodeLabel() {
        return episodeNumber == null ? null : String.format("S%d · Ep.%02d", season == null ? 1 : season, episodeNumber);
    }

    public String getSourceExcerpt() {
        return sourceExcerpt;
    }

    /** "Canto I, st. 19–41" for display, or null. */
    public String getSourceExcerptLabel() {
        return sourceExcerpt == null ? null : com.storyforge.series.SourceExcerpt.fromJson(sourceExcerpt).label();
    }

    public boolean isRecapEnabled() {
        return recapEnabled;
    }

    public String getChannelSnapshot() {
        return channelSnapshot;
    }

    public int getStoryQaRetries() {
        return storyQaRetries;
    }

    public int getStoryRejections() {
        return storyRejections;
    }

    public int getScriptQaRetries() {
        return scriptQaRetries;
    }

    public int getScriptRejections() {
        return scriptRejections;
    }

    public int getFinalRejections() {
        return finalRejections;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public Instant getAutoResumeAt() {
        return autoResumeAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

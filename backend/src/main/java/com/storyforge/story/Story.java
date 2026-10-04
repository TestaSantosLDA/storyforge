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

    private StoryStatus resumeStatus;
    private String attentionReason;

    @Enumerated(EnumType.STRING)
    private ArchiveReason archiveReason;

    private String topicSnapshot;

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
        m.put("queue_position", queuePosition);
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

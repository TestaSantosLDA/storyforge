package com.storyforge.log;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One attempt of an AI or engine step: inputs, prompt and model versions, raw output, QA results, counters,
 * cost and timestamps (CLAUDE.md rule 8). JSON columns hold whatever the stage records.
 */
@Entity
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long storyId;
    private String stage;
    private String step;
    private int attemptNo;
    private String promptVersion;
    private String model;
    private String engineVersion;
    private String inputHash;

    @JdbcTypeCode(SqlTypes.JSON)
    private String inputs;

    private String rawOutput;

    @JdbcTypeCode(SqlTypes.JSON)
    private String qaResults;

    @JdbcTypeCode(SqlTypes.JSON)
    private String counters;

    private BigDecimal costUsd = BigDecimal.ZERO;
    private String outcome;
    private String error;
    private Instant startedAt;
    private Instant finishedAt;

    protected Attempt() {
    }

    public Attempt(Long storyId, String stage, String step, int attemptNo) {
        this.storyId = storyId;
        this.stage = stage;
        this.step = step;
        this.attemptNo = attemptNo;
        this.startedAt = Instant.now();
    }

    public Attempt versions(String promptVersion, String model, String engineVersion) {
        this.promptVersion = promptVersion;
        this.model = model;
        this.engineVersion = engineVersion;
        return this;
    }

    public Attempt inputs(String inputHash, String inputsJson) {
        this.inputHash = inputHash;
        this.inputs = inputsJson;
        return this;
    }

    public Attempt result(String outcome, String rawOutput, String qaResultsJson, String countersJson,
            BigDecimal costUsd, String error) {
        this.outcome = outcome;
        this.rawOutput = rawOutput;
        this.qaResults = qaResultsJson;
        this.counters = countersJson;
        this.costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        this.error = error;
        this.finishedAt = Instant.now();
        return this;
    }

    public Long getId() {
        return id;
    }

    public Long getStoryId() {
        return storyId;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getOutcome() {
        return outcome;
    }
}

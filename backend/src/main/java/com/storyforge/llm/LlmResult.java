package com.storyforge.llm;

import java.math.BigDecimal;

/**
 * The structured answer plus what the attempt log needs. {@code billedUsd} is what was actually charged (0 on a
 * subscription); {@code listPriceUsd} is the engine's own estimate at list price, kept for comparison.
 */
public record LlmResult(String json, String raw, String model, BigDecimal billedUsd, BigDecimal listPriceUsd,
        long durationMs) {
}

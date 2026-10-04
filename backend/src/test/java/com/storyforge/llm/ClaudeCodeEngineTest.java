package com.storyforge.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storyforge.config.StoryforgeProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Parsing of real {@code claude -p --output-format json} results (shape captured from Claude Code 2.1.289). */
class ClaudeCodeEngineTest {

    ClaudeCodeEngine engine = new ClaudeCodeEngine(
            new StoryforgeProperties.Llm("claude-code", "claude", null, Duration.ofMinutes(1), 0, Duration.ZERO, Duration.ofHours(1)));

    @Test
    void success_returns_the_structured_output_and_list_price() {
        String out = """
                {"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.65,
                 "modelUsage":{"claude-fable-5-1":{"inputTokens":2}},
                 "result":"{\\"n\\":7}","structured_output":{"greeting":"Hello!","n":7}}
                """;
        LlmResult r = engine.parse(out, "", 0, 1234);
        assertThat(r.json()).isEqualTo("{\"greeting\":\"Hello!\",\"n\":7}");
        assertThat(r.model()).isEqualTo("claude-fable-5-1");
        assertThat(r.billedUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.listPriceUsd()).isEqualByComparingTo("0.65");
    }

    @Test
    void usage_limit_or_api_error_is_unavailable_not_a_strike() {
        String out = """
                {"type":"result","subtype":"error_during_execution","is_error":true,"result":"usage limit reached",
                 "api_error_status":429}
                """;
        assertThatThrownBy(() -> engine.parse(out, "", 1, 10)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void schema_failure_is_bad_output() {
        String out = """
                {"type":"result","subtype":"error_max_structured_output_retries","is_error":true,"result":""}
                """;
        assertThatThrownBy(() -> engine.parse(out, "", 1, 10)).isInstanceOf(LlmBadOutputException.class);
    }

    @Test
    void no_json_at_all_is_unavailable() {
        assertThatThrownBy(() -> engine.parse("", "not logged in", 1, 10))
                .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("not logged in");
    }

    @Test
    void windows_arguments_keep_their_quotes() {
        assertThat(ClaudeCodeEngine.windowsArg("{\"a\":\"b c\"}")).isEqualTo("{\\\"a\\\":\\\"b c\\\"}");
        assertThat(ClaudeCodeEngine.windowsArg("")).isEqualTo("\"\"");
        assertThat(ClaudeCodeEngine.windowsArg("path\\")).isEqualTo("path\\");
        assertThat(ClaudeCodeEngine.windowsArg("a b\\")).isEqualTo("a b\\\\");
    }

    @Test
    void usage_limit_reset_time_is_read_from_the_message() {
        assertThat(ClaudeCodeEngine.resetTime("Claude AI usage limit reached|1791160000"))
                .isEqualTo(java.time.Instant.ofEpochSecond(1791160000));
        java.time.Instant at = ClaudeCodeEngine.resetTime("You've hit your limit · resets 3pm (Europe/Lisbon)");
        assertThat(at).isNotNull();
        assertThat(java.time.ZonedDateTime.ofInstant(at, java.time.ZoneId.systemDefault()).getHour()).isEqualTo(15);
        assertThat(ClaudeCodeEngine.resetTime("something else went wrong")).isNull();
        assertThatThrownBy(() -> engine.parse("""
                {"type":"result","subtype":"error_during_execution","is_error":true,
                 "result":"Claude AI usage limit reached|1791160000"}
                """, "", 1, 10)).isInstanceOf(UsageLimitException.class);
    }
}

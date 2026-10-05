package com.storyforge.llm;

import com.storyforge.config.StoryforgeProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the Claude Code CLI headlessly ({@code claude -p}) on the user's own plan. Each call is one bounded step:
 * no tools, no session kept, our own system prompt, and a JSON schema the answer must match. It runs in an empty
 * working directory so no project files or CLAUDE.md leak into the prompt.
 */
public class ClaudeCodeEngine implements LlmEngine {

    private static final java.util.regex.Pattern USAGE_LIMIT = java.util.regex.Pattern.compile(
            "usage limit|limit reached|rate limit|limit will reset|resets at", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern EPOCH = java.util.regex.Pattern.compile("\\|(\\d{10})\\b");
    private static final java.util.regex.Pattern CLOCK = java.util.regex.Pattern.compile(
            "resets?\\s+(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private final StoryforgeProperties.Llm config;
    private final JsonMapper json = JsonMapper.builder().build();

    public ClaudeCodeEngine(StoryforgeProperties.Llm config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "claude-code";
    }

    @Override
    public LlmResult complete(LlmRequest req) {
        List<String> cmd = new ArrayList<>(List.of(config.command(), "-p",
                "--output-format", "json",
                "--no-session-persistence",
                "--strict-mcp-config",
                "--tools", req.images().isEmpty() ? "" : "Read",
                "--system-prompt", req.systemPrompt(),
                "--json-schema", json.writeValueAsString(json.readTree(req.jsonSchema()))));
        if (!req.images().isEmpty()) {
            // Vision QA: only the Read tool, only on the folders holding the images.
            cmd.addAll(List.of("--allowedTools", "Read"));
            req.images().stream().map(p -> p.toAbsolutePath().getParent().toString()).distinct()
                    .forEach(dir -> cmd.addAll(List.of("--add-dir", dir)));
        }
        if (config.model() != null && !config.model().isBlank()) {
            cmd.addAll(List.of("--model", config.model()));
        }
        if (WINDOWS) {
            cmd.replaceAll(ClaudeCodeEngine::windowsArg);
        }
        long start = System.currentTimeMillis();
        String stdout;
        String stderr;
        int exit;
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("storyforge-llm-");
            Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (var stdin = p.getOutputStream()) {
                stdin.write(req.userPrompt().getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new LlmUnavailableException("claude -p timed out after " + config.timeout());
            }
            exit = p.exitValue();
            stdout = out.join();
            stderr = err.join();
        } catch (IOException e) {
            throw new LlmUnavailableException("could not run " + config.command() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("interrupted", e);
        } finally {
            deleteQuietly(workDir);
        }
        return parse(stdout, stderr, exit, System.currentTimeMillis() - start);
    }

    LlmResult parse(String stdout, String stderr, int exit, long durationMs) {
        JsonNode root;
        try {
            root = json.readTree(stdout);
        } catch (RuntimeException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new LlmUnavailableException("claude -p exit " + exit + ", no JSON result: " + tail(stderr + stdout));
        }
        if (root.path("is_error").asBoolean(false) || !"success".equals(root.path("subtype").asString(""))) {
            String why = root.path("subtype").asString("") + " " + root.path("result").asString("")
                    + " " + root.path("api_error_status").asString("");
            if (root.path("subtype").asString("").contains("structured_output")) {
                throw new LlmBadOutputException("answer did not match the schema: " + why.strip(), stdout);
            }
            String text = why + " " + stderr;
            if (root.path("api_error_status").asInt(0) == 429 || USAGE_LIMIT.matcher(text).find()) {
                throw new UsageLimitException("usage limit reached: " + tail(why.strip()), resetTime(text));
            }
            throw new LlmUnavailableException("claude -p error: " + tail(why.strip()));
        }
        JsonNode structured = root.path("structured_output");
        if (structured.isMissingNode() || structured.isNull()) {
            throw new LlmBadOutputException("no structured_output in result", stdout);
        }
        // Claude Code may also make small side calls on another model; the main one wrote the most output.
        JsonNode usage = root.path("modelUsage");
        String model = usage.propertyNames().stream()
                .max(java.util.Comparator.comparingLong(m -> usage.path(m).path("outputTokens").asLong(0)))
                .orElse("unknown");
        BigDecimal listPrice = root.path("total_cost_usd").isNumber()
                ? root.path("total_cost_usd").decimalValue() : BigDecimal.ZERO;
        return new LlmResult(json.writeValueAsString(structured), stdout, model, BigDecimal.ZERO, listPrice,
                durationMs);
    }

    /**
     * The reset time, if the message carries one: an epoch after a "|" (older CLI format) or "resets 3pm" /
     * "resets at 15:00" (next occurrence, local time). Null if neither is found; callers then wait a fixed time.
     */
    static java.time.Instant resetTime(String text) {
        var epoch = EPOCH.matcher(text);
        if (epoch.find()) {
            return java.time.Instant.ofEpochSecond(Long.parseLong(epoch.group(1)));
        }
        var clock = CLOCK.matcher(text);
        if (clock.find()) {
            int hour = Integer.parseInt(clock.group(1));
            int minute = clock.group(2) == null ? 0 : Integer.parseInt(clock.group(2));
            String ampm = clock.group(3);
            if (ampm != null) {
                hour = hour % 12 + (ampm.equalsIgnoreCase("pm") ? 12 : 0);
            }
            if (hour > 23 || minute > 59) {
                return null;
            }
            var now = java.time.ZonedDateTime.now();
            var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            return (at.isAfter(now) ? at : at.plusDays(1)).toInstant();
        }
        return null;
    }

    /**
     * Java on Windows wraps an argument containing spaces in quotes but does not escape quotes inside it, so the
     * JSON schema would arrive broken. Escape them the way the C runtime parses them: {@code \"}, with any
     * backslashes before a quote (or at the end) doubled. An empty argument becomes {@code ""}.
     */
    static String windowsArg(String arg) {
        if (arg.isEmpty()) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder();
        int backslashes = 0;
        for (char c : arg.toCharArray()) {
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                out.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                out.append("\\".repeat(backslashes)).append(c);
            }
            backslashes = 0;
        }
        boolean wrapped = arg.chars().anyMatch(Character::isWhitespace);
        out.append("\\".repeat(wrapped ? backslashes * 2 : backslashes));
        return out.toString();
    }

    private static String read(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(String s) {
        return s.length() <= 500 ? s : s.substring(s.length() - 500);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir; the OS cleans it eventually
        }
    }
}

/*
 * Copyright (c) 2026-present Doug Hoard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package examples.harness.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.dhoard.braxton.style.TextMeasure;

/**
 * Reads the newline-delimited transcript format used by the bundled example and by Amanda session
 * logs: each line is a JSON object with a {@code kind} and a {@code payload}. This is a data-format
 * reader only; it does not import or adapt any Amanda type.
 *
 * <p>The reader understands the subset needed by the showcase ({@code input.attached},
 * {@code turn.started}, {@code assistant.completed}, {@code tool.completed}, {@code turn.completed},
 * {@code turn.failed}, and {@code metrics}). Unknown event types are ignored and malformed lines are
 * counted as warnings, so a newer log remains readable.</p>
 */
final class TranscriptJsonlReader {

    /**
     * Reads one record stream. A raw session-log line has a tab-separated prefix before the JSON
     * object; parsing starts at the first {@code {} so both the bundled pure-JSONL fixture and a
     * raw {@code session-log} line work.
     */
    HarnessTranscript read(Reader source) throws IOException {
        Map<String, String> inputTexts = new HashMap<>();
        Map<String, TurnBuilder> turns = new LinkedHashMap<>();
        Map<String, PendingTool> pendingTools = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        State state = new State();

        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String json = line.strip();
                if (json.isEmpty()) {
                    continue;
                }
                int brace = json.indexOf('{');
                if (brace > 0) {
                    json = json.substring(brace);
                }
                try {
                    JsonElement parsed = JsonParser.parseString(json);
                    if (!parsed.isJsonObject()) {
                        warnings.add("Line " + lineNumber + ": record is not an object");
                        continue;
                    }
                    readRecord(parsed.getAsJsonObject(), state, inputTexts, turns, pendingTools,
                            warnings);
                } catch (RuntimeException failure) {
                    warnings.add("Line " + lineNumber + ": " + failure.getClass().getSimpleName());
                }
            }
        }

        List<HarnessTranscript.HarnessTurn> built = new ArrayList<>();
        for (TurnBuilder turn : turns.values()) {
            if (turn.userText.isBlank() && turn.responses.isEmpty()) {
                continue;
            }
            built.add(new HarnessTranscript.HarnessTurn(turn.userText, turn.responses,
                    turn.usage, turn.profile));
        }
        HarnessTranscript.Profile profile = state.profile == null
                ? HarnessTranscript.Profile.DEFAULT : state.profile;
        HarnessTranscript.Usage usage = state.usage == null
                ? HarnessTranscript.Usage.EMPTY : state.usage;
        return new HarnessTranscript(built, profile, usage,
                HarnessTranscript.Profile.DEFAULT_CONTEXT_WINDOW, warnings);
    }

    HarnessTranscript read(InputStream source) throws IOException {
        return read(new InputStreamReader(source, StandardCharsets.UTF_8));
    }

    private static void readRecord(JsonObject record, State state, Map<String, String> inputTexts,
            Map<String, TurnBuilder> turns, Map<String, PendingTool> pendingTools,
            List<String> warnings) {
        JsonObject payload = object(record, "payload");
        if (payload == null) {
            warnings.add("Record without a payload object");
            return;
        }
        if (!"event".equals(string(record, "kind"))) {
            return;
        }
        String type = string(payload, "type");
        if (type == null) {
            warnings.add("Event without a type");
            return;
        }
        switch (type) {
            case "input.attached" -> {
                String inputId = string(payload, "inputId");
                String text = HarnessRender.sanitize(string(payload, "text"));
                if (inputId != null) {
                    inputTexts.put(inputId, text);
                }
            }
            case "turn.started" -> {
                String turnId = turnId(payload, state);
                TurnBuilder turn = turnFor(turns, turnId);
                turn.inputId = string(payload, "inputId");
                if (turn.inputId != null) {
                    turn.userText = inputTexts.getOrDefault(turn.inputId, turn.userText);
                }
                updateProfile(turn, payload, state);
                state.currentTurnId = turnId;
            }
            case "assistant.completed" -> {
                TurnBuilder turn = turnFor(turns, turnId(payload, state));
                updateProfile(turn, payload, state);
                updateUsage(turn, payload, state);
                String reasoning = HarnessRender.sanitize(string(payload, "reasoning"));
                if (!reasoning.isBlank()) {
                    turn.responses.add(new HarnessEntry.Reasoning(reasoning));
                }
                JsonArray tools = array(payload, "tools");
                if (tools != null) {
                    for (JsonElement element : tools) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        readTool(element.getAsJsonObject(), turn, pendingTools, state);
                    }
                }
                String text = HarnessRender.sanitize(string(payload, "text"));
                if (!text.isBlank()) {
                    turn.responses.add(new HarnessEntry.Assistant(text));
                }
            }
            case "tool.completed" -> {
                TurnBuilder turn = turnFor(turns, turnId(payload, state));
                String callId = string(payload, "toolCallId");
                String name = string(payload, "toolName");
                PendingTool pending = callId == null ? null : pendingTools.remove(callId);
                String arguments = string(payload, "arguments");
                if (pending != null) {
                    name = pending.name;
                    arguments = pending.arguments;
                }
                String call = formatCall(name, arguments);
                String result = HarnessRender.sanitize(string(payload, "text"));
                boolean error = booleanValue(payload, "error", false);
                boolean truncated = booleanValue(payload, "truncated", false);
                turn.responses.add(new HarnessEntry.Tool(call, result, truncated,
                        error ? HarnessEntry.ToolStatus.ERROR : HarnessEntry.ToolStatus.SUCCESS));
                updateProfile(turn, payload, state);
            }
            case "turn.completed" -> {
                TurnBuilder turn = turnFor(turns, turnId(payload, state));
                updateProfile(turn, payload, state);
                updateUsage(turn, payload, state);
            }
            case "turn.failed" -> {
                TurnBuilder turn = turnFor(turns, turnId(payload, state));
                updateProfile(turn, payload, state);
                updateUsage(turn, payload, state);
                String text = HarnessRender.sanitize(string(payload, "text"));
                turn.responses.add(new HarnessEntry.Failure(
                        text.isBlank() ? "The example turn failed." : text));
            }
            case "metrics" -> {
                TurnBuilder turn = turnFor(turns, turnId(payload, state));
                updateProfile(turn, payload, state);
                updateMetrics(turn, payload, state);
            }
            default -> {
                // Unknown event types are forward-compatible noise, not warnings.
            }
        }
    }

    private static void readTool(JsonObject tool, TurnBuilder turn,
            Map<String, PendingTool> pendingTools, State state) {
        String name = string(tool, "name");
        String arguments = string(tool, "arguments");
        String callId = string(tool, "toolCallId");
        String result = HarnessRender.sanitize(string(tool, "result"));
        boolean error = booleanValue(tool, "error", false);
        boolean truncated = booleanValue(tool, "truncated", false);
        String status = string(tool, "status");
        boolean completed = result != null && !result.isBlank()
                || "completed".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status);
        if (!completed) {
            if (callId != null) {
                pendingTools.put(callId, new PendingTool(name, arguments));
                return;
            }
            // Nothing will complete this call, so keep it visibly pending instead of claiming
            // a successful result that was never recorded.
            turn.responses.add(new HarnessEntry.Tool(formatCall(name, arguments), result,
                    truncated, HarnessEntry.ToolStatus.PENDING));
            return;
        }
        turn.responses.add(new HarnessEntry.Tool(formatCall(name, arguments), result, truncated,
                error ? HarnessEntry.ToolStatus.ERROR : HarnessEntry.ToolStatus.SUCCESS));
    }

    private static String turnId(JsonObject payload, State state) {
        String turnId = string(payload, "turnId");
        return turnId == null || turnId.isBlank() ? state.currentTurnId : turnId;
    }

    private static TurnBuilder turnFor(Map<String, TurnBuilder> turns, String turnId) {
        String key = turnId == null || turnId.isBlank() ? "" : turnId;
        return turns.computeIfAbsent(key, ignored -> new TurnBuilder());
    }

    private static void updateProfile(TurnBuilder turn, JsonObject payload, State state) {
        JsonObject profile = object(payload, "profile");
        if (profile == null) {
            return;
        }
        HarnessTranscript.Profile parsed = new HarnessTranscript.Profile(
                string(profile, "provider"), string(profile, "model"),
                string(profile, "reasoning"));
        turn.profile = parsed;
        state.profile = parsed;
    }

    private static void updateUsage(TurnBuilder turn, JsonObject payload, State state) {
        JsonObject usage = object(payload, "usage");
        if (usage == null) {
            return;
        }
        turn.usage = mergeUsage(turn.usage, usage);
        state.usage = mergeUsage(state.usage, usage);
    }

    private static void updateMetrics(TurnBuilder turn, JsonObject payload, State state) {
        HarnessTranscript.Usage base = turn.usage == null
                ? HarnessTranscript.Usage.EMPTY : turn.usage;
        HarnessTranscript.Usage merged = mergeUsage(base, payload, true);
        turn.usage = merged;
        state.usage = mergeUsage(state.usage, payload, true);
    }

    private static HarnessTranscript.Usage mergeUsage(HarnessTranscript.Usage base,
            JsonObject values) {
        return mergeUsage(base, values, false);
    }

    private static HarnessTranscript.Usage mergeUsage(HarnessTranscript.Usage base,
            JsonObject values, boolean metrics) {
        HarnessTranscript.Usage safe = base == null ? HarnessTranscript.Usage.EMPTY : base;
        long input = longValue(values, "inputTokens", safe.inputTokens());
        long output = longValue(values, "outputTokens", safe.outputTokens());
        long cached = longValue(values, "cachedInputTokens", safe.cachedInputTokens());
        long uncached = longValue(values, "uncachedInputTokens", safe.uncachedInputTokens());
        long context = metrics
                ? longValue(values, "currentContextTokens",
                        Math.max(safe.contextTokens(), input + output))
                : Math.max(safe.contextTokens(), input + output);
        Double rate = doubleValue(values, "generationTokensPerSecond", safe.generationRate());
        return new HarnessTranscript.Usage(input, output, cached, uncached, context, rate);
    }

    /** Formats a tool call as one readable line without exposing raw JSON. */
    static String formatCall(String name, String arguments) {
        String tool = name == null || name.isBlank() ? "tool" : name.strip();
        JsonObject args = parseObject(arguments);
        String path = string(args, "path");
        String call = switch (tool.toLowerCase(Locale.ROOT)) {
            case "read" -> "read" + suffix(path);
            case "ls" -> "ls" + suffix(path == null || path.isBlank() ? "." : path);
            case "write" -> "write" + suffix(path);
            case "edit" -> "edit" + suffix(path);
            case "bash" -> "$ " + defaultString(string(args, "command"), "...");
            case "grep" -> "grep /" + defaultString(string(args, "pattern"), "")
                    + "/ in " + defaultString(path, ".");
            case "find" -> "find " + defaultString(string(args, "pattern"), "")
                    + " in " + defaultString(path, ".");
            default -> tool + (arguments == null || arguments.isBlank()
                    ? "" : " " + compact(arguments));
        };
        return HarnessRender.sanitize(call).replace("\n", " ");
    }

    private static String suffix(String path) {
        return path == null || path.isBlank() ? "" : " " + path;
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String compact(String value) {
        String compact = value.replaceAll("\\s+", " ").strip();
        if (compact.length() <= 80) {
            return compact;
        }
        // The budget is in UTF-16 units, so end the kept prefix on a grapheme boundary: a
        // surrogate pair or an emoji sequence must never reach the view half-formed.
        int end = TextMeasure.boundaryBefore(compact, 79);
        return compact.substring(0, end) + "\u2026";
    }

    private static JsonObject parseObject(String value) {
        if (value == null || value.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static boolean booleanValue(JsonObject parent, String key, boolean fallback) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : fallback;
    }

    private static long longValue(JsonObject parent, String key, long fallback) {
        JsonElement element = parent == null ? null : parent.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsLong();
    }

    private static Double doubleValue(JsonObject parent, String key, Double fallback) {
        JsonElement element = parent == null ? null : parent.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsDouble();
    }

    private static final class State {
        String currentTurnId;
        HarnessTranscript.Profile profile;
        HarnessTranscript.Usage usage;
    }

    private static final class TurnBuilder {
        String inputId;
        String userText = "";
        HarnessTranscript.Profile profile;
        HarnessTranscript.Usage usage;
        final List<HarnessEntry> responses = new ArrayList<>();
    }

    private record PendingTool(String name, String arguments) {
    }
}

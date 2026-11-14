package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.*;
import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AiStreamClient {
    private AiStreamClient() {}

    /** The source protocol is retained even when the same UI displays these events. */
    public enum EventType {
        OPENAI_REASONING_SUMMARY, DEEPSEEK_REASONING, GEMINI_THOUGHT_SUMMARY,
        ANTHROPIC_THINKING, RAW_REASONING, FINAL_ANSWER
    }

    public static class Delta {
        private final String reasoning;
        private final String content;
        private final EventType type;
        public Delta(String reasoning, String content) {
            this(reasoning, content, reasoning != null && !reasoning.isEmpty()
                    ? EventType.RAW_REASONING : EventType.FINAL_ANSWER);
        }
        public Delta(String reasoning, String content, EventType type) {
            this.reasoning = reasoning;
            this.content = content;
            this.type = java.util.Objects.requireNonNull(type);
        }
        public EventType type() { return type; }
        public String reasoning() { return reasoning; }
        public String content() { return content; }
    }

    public static ApiAdapter.AdapterResponse send(
            HttpClient client,
            ApiAdapter adapter,
            HttpRequest original,
            Consumer<Delta> listener
    ) throws Exception {
        HttpRequest request = toStreamingRequest(adapter, original);

        Duration timeout = request.timeout().orElse(Duration.ofSeconds(120));
        long deadline = System.nanoTime() + timeout.toNanos();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new HttpTimeoutException("AI request timed out");
        }

        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "McAI-stream-timeout");
            thread.setDaemon(true);
            return thread;
        });

        try (InputStream body = response.body()) {
            long remaining = Math.max(0, deadline - System.nanoTime());
            ScheduledFuture<?> timeoutTask = watchdog.schedule(() -> {
                timedOut.set(true);
                closeQuietly(body);
            }, remaining, TimeUnit.NANOSECONDS);

            try {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ApiHttpException(response.statusCode());
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                    throw new ProtocolException("Provider did not return text/event-stream");
                }

                Accumulator accumulator = new Accumulator(listener, adapter);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    readEvents(reader, adapter, accumulator);
                }

                if (timedOut.get()) throw new HttpTimeoutException("AI stream timed out");
                if (!accumulator.completed) throw new IOException("AI stream closed before its completion event");

                return new ApiAdapter.AdapterResponse(
                        accumulator.content.toString(),
                        accumulator.reasoning.length() == 0 ? null : accumulator.reasoning.toString(),
                        accumulator.cacheUsage, accumulator.signature(), accumulator.blocks()
                );
            } catch (IOException e) {
                if (timedOut.get()) throw new HttpTimeoutException("AI stream timed out");
                throw e;
            } finally {
                timeoutTask.cancel(false);
            }
        } finally {
            watchdog.shutdownNow();
        }
    }

    public static final class ProtocolException extends IOException {
        ProtocolException(String message) { super(message); }
        ProtocolException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class ApiHttpException extends IOException {
        private final int statusCode;
        public ApiHttpException(int statusCode) {
            super("AI HTTP " + statusCode);
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
    }

    private static HttpRequest toStreamingRequest(ApiAdapter adapter, HttpRequest original) throws Exception {
        URI uri = original.uri();
        if (adapter instanceof GeminiAdapter) {
            String url = uri.toString();
            if (!url.contains(":generateContent")) {
                throw new ProtocolException("Invalid Gemini endpoint");
            }
            url = url.replace(":generateContent", ":streamGenerateContent");
            url += url.contains("?") ? "&alt=sse" : "?alt=sse";
            uri = URI.create(url);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(original.timeout().orElse(Duration.ofSeconds(120)));
        original.headers().map().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Accept") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        builder.header("Accept", "text/event-stream");
        builder.method(original.method(), original.bodyPublisher().orElseThrow(
                () -> new IOException("Missing request body")));
        return builder.build();
    }

    private static void readEvents(BufferedReader reader, ApiAdapter adapter, Accumulator accumulator) throws Exception {
        StringBuilder data = new StringBuilder();
        String eventName = "";
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    parseEvent(adapter, eventName, data.toString(), accumulator);
                    data.setLength(0);
                    if (accumulator.completed) return;
                }
                eventName = "";
                continue;
            }
            if (line.startsWith(":")) continue;

            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : line.substring(separator + 1);
            if (value.startsWith(" ")) value = value.substring(1);

            if ("event".equals(field)) {
                eventName = value;
            } else if ("data".equals(field)) {
                if (data.length() > 0) data.append('\n');
                data.append(value);
            }
        }

        if (data.length() > 0) {
            parseEvent(adapter, eventName, data.toString(), accumulator);
        }
    }

    private static void parseEvent(ApiAdapter adapter, String eventName, String data, Accumulator accumulator) throws Exception {
        String trimmed = data.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return;
        if ("[DONE]".equals(trimmed)) {
            if (adapter instanceof AnthropicAdapter) throw new ProtocolException("Anthropic completion event missing");
            accumulator.completed = true;
            return;
        }

        if ("error".equals(eventName)) throw new ProtocolException("Provider returned a stream error");
        // Gate non-JSON keepalives before parsing, but never swallow damaged JSON payloads.
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(trimmed);
        } catch (JsonParseException e) {
            throw new ProtocolException("Provider returned invalid stream JSON", e);
        }
        if (!parsed.isJsonObject()) throw new ProtocolException("Expected a stream JSON object");
        JsonObject root = parsed.getAsJsonObject();
        String type = string(root, "type");
        ApiAdapter.CacheUsage usage = CacheUsageParser.parse(root);
        if (usage != null) accumulator.cacheUsage = usage;

        if ("error".equals(type) || root.has("error")) {
            throw new ProtocolException("Provider returned a stream error");
        }

        if (adapter instanceof OpenAIResponsesAdapter) {
            parseResponses(root, type, accumulator);
        } else if (adapter instanceof AnthropicAdapter) {
            parseAnthropic(root, type, accumulator);
        } else if (adapter instanceof GeminiAdapter) {
            parseGemini(root, accumulator);
        } else {
            parseChatCompletions(root, accumulator);
        }
    }

    private static void parseChatCompletions(JsonObject root, Accumulator accumulator) throws IOException {
        JsonArray choices = array(root, "choices");
        if (choices == null || choices.size() == 0) return;

        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = object(choice, "delta");

        if (delta != null) {
            String reasoning = string(delta, "reasoning_content");
            if (reasoning.isEmpty()) reasoning = string(delta, "reasoning");
            accumulator.emit(reasoning, string(delta, "content"));
        }

        String finishReason = string(choice, "finish_reason");
        if ("content_filter".equals(finishReason)) {
            throw new ProtocolException("Provider filtered the response");
        }
        if (!finishReason.isEmpty()) {
            accumulator.completed = true;
        }
    }

    private static void parseResponses(JsonObject root, String type, Accumulator accumulator) throws IOException {
        if ("response.reasoning_summary_part.added".equals(type)) {
            accumulator.summaryState(root);
        } else if ("response.reasoning_summary_text.delta".equals(type)) {
            String key = accumulator.summaryState(root);
            String delta = string(root, "delta");
            if (!delta.isEmpty()) { accumulator.summaryDeltas.add(key); accumulator.emit(delta, ""); }
        } else if ("response.reasoning_summary_text.done".equals(type)
                || "response.reasoning_summary_part.done".equals(type)) {
            String key = accumulator.summaryState(root);
            if (!accumulator.summaryDeltas.contains(key)) {
                JsonObject part = object(root, "part");
                String complete = "response.reasoning_summary_text.done".equals(type) ? string(root, "text") : string(part, "text");
                if (!complete.isEmpty()) { accumulator.summaryDeltas.add(key); accumulator.emit(complete, ""); }
            }
        } else if ("response.output_text.delta".equals(type)) {
            accumulator.emit("", string(root, "delta"));
        } else if ("response.completed".equals(type)) {
            JsonObject response = object(root, "response");
            if (response != null && !"completed".equals(string(response, "status"))) {
                throw new ProtocolException("Responses request did not complete");
            }
            accumulator.completed = true;
        } else if ("response.failed".equals(type) || "response.incomplete".equals(type)) {
            throw new ProtocolException("Responses request failed or incomplete");
        }
    }

    private static void parseAnthropic(JsonObject root, String type, Accumulator accumulator) throws IOException {
        if ("message_stop".equals(type)) {
            if (accumulator.anthropic.values().stream().anyMatch(block -> !block.stopped))
                throw new ProtocolException("Anthropic message ended with an open content block");
            accumulator.completed = true;
            return;
        }
        if (!type.startsWith("content_block_")) return;
        if (!root.has("index")) throw new ProtocolException("Anthropic content block has no index");
        int index = root.get("index").getAsInt();
        if (index < 0) throw new ProtocolException("Invalid Anthropic content block index");
        if ("content_block_start".equals(type)) {
            JsonObject value = object(root, "content_block");
            if (value == null || accumulator.anthropic.containsKey(index))
                throw new ProtocolException("Invalid Anthropic content block start");
            AnthropicBlock block = new AnthropicBlock(string(value, "type"));
            accumulator.anthropic.put(index, block);
            block.text.append(string(value, "thinking"));
            block.text.append(string(value, "text"));
            if ("redacted_thinking".equals(block.type)) block.text.append(string(value, "data"));
            block.signature.append(string(value, "signature"));
            if ("thinking".equals(block.type)) accumulator.emit(block.text.toString(), "");
            if ("text".equals(block.type)) accumulator.emit("", block.text.toString());
            return;
        }
        AnthropicBlock block = accumulator.anthropic.get(index);
        if (block == null || block.stopped) throw new ProtocolException("Inactive Anthropic content block");
        if ("content_block_stop".equals(type)) { block.stopped = true; return; }
        JsonObject delta = object(root, "delta");
        if (delta == null) throw new ProtocolException("Missing Anthropic delta");
        String deltaType = string(delta, "type");
        if ("signature_delta".equals(deltaType) && "thinking".equals(block.type)) {
            block.signature.append(string(delta, "signature"));
        } else if ("thinking_delta".equals(deltaType) && "thinking".equals(block.type)) {
            String text = string(delta, "thinking"); block.text.append(text); accumulator.emit(text, "");
        } else if ("text_delta".equals(deltaType) && "text".equals(block.type)) {
            String text = string(delta, "text"); block.text.append(text); accumulator.emit("", text);
        } else throw new ProtocolException("Unexpected Anthropic content block delta");
    }

    private static final class AnthropicBlock {
        final String type;
        final StringBuilder text = new StringBuilder(), signature = new StringBuilder();
        boolean stopped;
        AnthropicBlock(String type) { this.type = type; }
    }

    private static void parseGemini(JsonObject root, Accumulator accumulator) throws IOException {
        JsonArray candidates = array(root, "candidates");
        if (candidates == null || candidates.size() == 0) {
            if (root.has("promptFeedback")) throw new ProtocolException("Gemini blocked the prompt");
            return;
        }

        JsonObject candidate = candidates.get(0).getAsJsonObject();
        JsonObject content = object(candidate, "content");
        JsonArray parts = content == null ? null : array(content, "parts");

        if (parts != null) {
            for (JsonElement element : parts) {
                JsonObject part = element.getAsJsonObject();
                String text = string(part, "text");
                boolean thought = part.has("thought") && !part.get("thought").isJsonNull()
                        && part.get("thought").getAsBoolean();
                accumulator.geminiParts.add(GeminiAdapter.messagePart(part));
                accumulator.emit(thought ? text : "", thought ? "" : text);
            }
        }

        String finishReason = string(candidate, "finishReason");
        if (!finishReason.isEmpty()) {
            if (!"STOP".equals(finishReason) && !"MAX_TOKENS".equals(finishReason)) {
                throw new ProtocolException("Gemini response was blocked");
            }
            accumulator.completed = true;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.isJsonPrimitive()
                ? element.getAsString() : "";
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) {}
    }

    private static final class Accumulator {
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private final Consumer<Delta> listener;
        private ApiAdapter.CacheUsage cacheUsage;
        private boolean completed;
        private final java.util.Set<String> summaryDeltas = new java.util.HashSet<>();
        private final java.util.LinkedHashSet<String> summaryParts = new java.util.LinkedHashSet<>();
        private String summaryState(JsonObject root) {
            String key = string(root, "item_id") + "\0" + string(root, "output_index") + "\0" + string(root, "summary_index");
            summaryParts.add(key); return key;
        }
        private final java.util.SortedMap<Integer, AnthropicBlock> anthropic = new java.util.TreeMap<>();
        private final java.util.List<ApiAdapter.Message> geminiParts = new java.util.ArrayList<>();
        private java.util.List<ApiAdapter.Message> blocks() {
            if (!geminiParts.isEmpty()) return java.util.List.copyOf(geminiParts);
            return anthropic.values().stream().map(b -> new ApiAdapter.Message("assistant", b.text.toString(),
                    b.type, b.signature.isEmpty() ? null : b.signature.toString())).toList();
        }
        private String signature() {
            return anthropic.values().stream().filter(b -> !b.signature.isEmpty())
                    .map(b -> b.signature.toString()).findFirst().orElse(null);
        }

        private final EventType reasoningType;
        private Accumulator(Consumer<Delta> listener, ApiAdapter adapter) {
            this.listener = listener;
            reasoningType = adapter instanceof OpenAIResponsesAdapter ? EventType.OPENAI_REASONING_SUMMARY
                    : adapter instanceof DeepSeekAdapter ? EventType.DEEPSEEK_REASONING
                    : adapter instanceof GeminiAdapter ? EventType.GEMINI_THOUGHT_SUMMARY
                    : adapter instanceof AnthropicAdapter ? EventType.ANTHROPIC_THINKING
                    : EventType.RAW_REASONING;
        }

        private void emit(String reasoningDelta, String contentDelta) {
            if (reasoningDelta.isEmpty() && contentDelta.isEmpty()) return;
            reasoning.append(reasoningDelta);
            content.append(contentDelta);
            if (!reasoningDelta.isEmpty()) listener.accept(new Delta(reasoningDelta, "", reasoningType));
            if (!contentDelta.isEmpty()) listener.accept(new Delta("", contentDelta, EventType.FINAL_ANSWER));
        }
    }
}

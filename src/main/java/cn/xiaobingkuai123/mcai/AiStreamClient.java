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

    public static class Delta {
        private final String reasoning;
        private final String content;
        public Delta(String reasoning, String content) {
            this.reasoning = reasoning;
            this.content = content;
        }
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

                Accumulator accumulator = new Accumulator(listener);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    readEvents(reader, adapter, accumulator);
                }

                if (timedOut.get()) throw new HttpTimeoutException("AI stream timed out");
                if (!accumulator.completed) throw new IOException("AI stream closed before its completion event");

                return new ApiAdapter.AdapterResponse(
                        accumulator.content.toString(),
                        accumulator.reasoning.length() == 0 ? null : accumulator.reasoning.toString(),
                        accumulator.cacheUsage
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
            accumulator.completed = true;
            return;
        }

        if ("error".equals(eventName)) throw new ProtocolException("Provider returned a stream error");
        // Gate non-JSON keepalives before parsing, but never swallow damaged JSON payloads.
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return;
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(trimmed);
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
        if ("response.reasoning_summary_text.delta".equals(type)) {
            accumulator.emit(string(root, "delta"), "");
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

    private static void parseAnthropic(JsonObject root, String type, Accumulator accumulator) {
        if ("message_stop".equals(type)) {
            accumulator.completed = true;
            return;
        }
        if (!"content_block_delta".equals(type)) return;

        JsonObject delta = object(root, "delta");
        if (delta == null) return;

        String deltaType = string(delta, "type");
        if ("thinking_delta".equals(deltaType)) {
            accumulator.emit(string(delta, "thinking"), "");
        } else if ("text_delta".equals(deltaType)) {
            accumulator.emit("", string(delta, "text"));
        }
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

        private Accumulator(Consumer<Delta> listener) { this.listener = listener; }

        private void emit(String reasoningDelta, String contentDelta) {
            if (reasoningDelta.isEmpty() && contentDelta.isEmpty()) return;
            reasoning.append(reasoningDelta);
            content.append(contentDelta);
            listener.accept(new Delta(reasoningDelta, contentDelta));
        }
    }
}

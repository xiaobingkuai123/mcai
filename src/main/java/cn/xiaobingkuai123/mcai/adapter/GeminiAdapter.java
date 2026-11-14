package cn.xiaobingkuai123.mcai.adapter;

import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

/** GenerateContent protocol; thought summaries and opaque signatures are separate parts. */
public class GeminiAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public GeminiAdapter(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override public HttpRequest buildRequest(String baseUrl, String key, String model, int max,
            List<Message> messages, boolean thinking) throws Exception {
        return buildRequest(baseUrl, key, model, messages,
                new ApiRequestOptions(.7, 1, max, timeoutSeconds, 0, false, thinking, 4096, "medium"));
    }

    @Override public HttpRequest buildRequest(String baseUrl, String key, String model,
            List<Message> messages, ApiRequestOptions options) {
        JsonObject system = null;
        JsonArray contents = new JsonArray();
        JsonObject previous = null;
        for (Message message : messages) {
            JsonObject part = new JsonObject();
            part.addProperty("text", message.content());
            if ("system".equals(message.role())) {
                system = new JsonObject();
                JsonArray parts = new JsonArray(); parts.add(part); system.add("parts", parts);
                continue;
            }
            if ("gemini_thought".equals(message.blockType())) part.addProperty("thought", true);
            if (message.signature() != null) part.addProperty("thoughtSignature", message.signature());
            String role = "assistant".equals(message.role()) ? "model" : "user";
            // Preserve part boundaries and signature attachment when replaying a model turn.
            if (previous == null || !"model".equals(role) || !message.blockType().startsWith("gemini_")
                    || !role.equals(previous.get("role").getAsString())) {
                previous = new JsonObject(); previous.addProperty("role", role);
                previous.add("parts", new JsonArray()); contents.add(previous);
            }
            previous.getAsJsonArray("parts").add(part);
        }
        JsonObject generation = new JsonObject();
        generation.addProperty("maxOutputTokens", options.maxTokens());
        generation.addProperty("temperature", options.temperature());
        generation.addProperty("topP", options.topP());
        generation.add("thinkingConfig", thinkingConfig(model, options));
        JsonObject body = new JsonObject();
        if (options.cachedContent() != null && !options.cachedContent().isBlank()) {
            body.addProperty("cachedContent", options.cachedContent());
            JsonArray current = new JsonArray();
            if (!contents.isEmpty()) current.add(contents.get(contents.size() - 1));
            contents = current;
        } else if (system != null) body.add("systemInstruction", system);
        body.add("contents", contents); body.add("generationConfig", generation);
        String url = baseUrl.replaceAll("/$", "") + "/v1beta/models/"
                + (model.startsWith("models/") ? model.substring(7) : model) + ":generateContent";
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("x-goog-api-key", key).timeout(Duration.ofSeconds(options.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    static JsonObject thinkingConfig(String model, ApiRequestOptions options) {
        JsonObject config = new JsonObject();
        config.addProperty("includeThoughts", options.includeThoughts());
        String name = model.toLowerCase(Locale.ROOT).replaceFirst("^models/", "");
        // Gemini 3 supports levels; budget remains the compatible path for budget models/gateways.
        // This selects the strength parameter, never whether native thought parts may be displayed.
        if (name.startsWith("gemini-3")) {
            String effort = options.reasoningEffort() == null ? "high" : options.reasoningEffort().toLowerCase(Locale.ROOT);
            boolean flash = name.contains("flash");
            String level = switch (effort) {
                case "minimal" -> flash ? "minimal" : "low";
                case "low" -> "low";
                case "medium" -> flash ? "medium" : "high";
                default -> "high";
            };
            config.addProperty("thinkingLevel", options.thinkingEnabled() ? level : (flash ? "minimal" : "low"));
        } else {
            int budget = options.thinkingEnabled() ? Math.min(options.maxTokens(), options.thinkingBudget()) : 0;
            // 2.5 Pro cannot disable thinking or use a budget below 128 tokens.
            if (name.startsWith("gemini-2.5-pro")) budget = Math.max(128, budget);
            config.addProperty("thinkingBudget", budget);
        }
        return config;
    }

    /** Keep signatures on their original part; never decode or expose them as display text. */
    public static Message messagePart(JsonObject part) {
        return new Message("assistant", text(part, "text"),
                part.has("thought") && !part.get("thought").isJsonNull() && part.get("thought").getAsBoolean()
                        ? "gemini_thought" : "gemini_text", nullableText(part, "thoughtSignature"));
    }
    private static String nullableText(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }
    private static String text(JsonObject object, String key) {
        String value = nullableText(object, key); return value == null ? "" : value;
    }

    @Override public AdapterResponse parseResponse(String text) {
        JsonArray candidates = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) return new AdapterResponse("", null);
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        JsonArray parts = content == null ? null : content.getAsJsonArray("parts");
        StringBuilder answer = new StringBuilder(), thoughts = new StringBuilder();
        List<Message> blocks = new ArrayList<>();
        if (parts != null) for (JsonElement element : parts) {
            Message part = messagePart(element.getAsJsonObject()); blocks.add(part);
            ("gemini_thought".equals(part.blockType()) ? thoughts : answer).append(part.content());
        }
        return new AdapterResponse(answer.toString(), thoughts.isEmpty() ? null : thoughts.toString(), null, null, blocks);
    }
}

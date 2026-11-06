package cn.xiaobingkuai123.mcai.adapter;

import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class AnthropicAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    private final String apiVersion;
    public AnthropicAdapter(int timeoutSeconds) { this(timeoutSeconds, "2023-06-01"); }
    public AnthropicAdapter(int timeoutSeconds, String apiVersion) {
        this.timeoutSeconds = timeoutSeconds;
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "2023-06-01" : apiVersion;
    }
    @Override public HttpRequest buildRequest(String baseUrl, String key, String model, int max, List<Message> messages, boolean thinking) throws Exception {
        return buildRequest(baseUrl, key, model, messages, new ApiRequestOptions(.7, 1, max, timeoutSeconds, 0, false, thinking, 4096, "medium"));
    }
    @Override public HttpRequest buildRequest(String baseUrl, String key, String model, List<Message> messages, ApiRequestOptions o) {
        JsonArray a = new JsonArray();
        StringBuilder system = new StringBuilder();
        for (Message m : messages) {
            if ("system".equals(m.role())) {
                if (!system.isEmpty()) system.append("\n\n");
                system.append(m.content());
                continue;
            }
            String role = "tool".equals(m.role()) ? "user" : m.role();
            JsonObject item;
            JsonArray blocks;
            if (a.size() > 0 && role.equals(a.get(a.size() - 1).getAsJsonObject().get("role").getAsString())) {
                item = a.get(a.size() - 1).getAsJsonObject();
                blocks = item.getAsJsonArray("content");
            } else {
                item = new JsonObject();
                item.addProperty("role", role);
                blocks = new JsonArray();
                item.add("content", blocks);
                a.add(item);
            }
            blocks.add(contentBlock(m));
        }
        JsonArray systemBlocks = new JsonArray();
        if (!system.isEmpty()) {
            JsonObject block = new JsonObject(); block.addProperty("type", "text"); block.addProperty("text", system.toString());
            JsonObject cache = new JsonObject(); cache.addProperty("type", "ephemeral"); block.add("cache_control", cache); systemBlocks.add(block);
        }
        if (a.size() > 1) {
            JsonObject cachedMessage = a.get(a.size() - 2).getAsJsonObject();
            JsonArray blocks = cachedMessage.getAsJsonArray("content");
            if (blocks != null && blocks.size() > 0) {
                JsonObject cache = new JsonObject(); cache.addProperty("type", "ephemeral");
                blocks.get(blocks.size() - 1).getAsJsonObject().add("cache_control", cache);
            }
        }
        JsonObject body = new JsonObject(); body.addProperty("model", model); body.add("messages", a);
        if (systemBlocks.size() > 0) body.add("system", systemBlocks);
        body.addProperty("max_tokens", o.maxTokens()); body.addProperty("stream", o.streaming());
        if (o.thinkingEnabled()) {
            int budget = Math.min(o.thinkingBudget(), o.maxTokens() - 1);
            if (budget < 1024) throw new IllegalArgumentException("Anthropic maxTokens must exceed thinking budget and be at least 1025");
            JsonObject t = new JsonObject(); t.addProperty("type", "enabled"); t.addProperty("budget_tokens", budget); body.add("thinking", t);
        } else { body.addProperty("temperature", o.temperature()); body.addProperty("top_p", o.topP()); }
        return HttpRequest.newBuilder().uri(URI.create(ApiUrls.v1(baseUrl, "/messages")))
                .header("Content-Type", "application/json").header("x-api-key", key).header("anthropic-version", apiVersion)
                .timeout(Duration.ofSeconds(o.timeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    private static JsonElement contentBlock(Message message) {
        String type = message.blockType() == null ? "text" : message.blockType();
        if ("thinking".equals(type)) {
            JsonObject block = new JsonObject(); block.addProperty("type", "thinking"); block.addProperty("thinking", message.content());
            if (message.signature() != null) block.addProperty("signature", message.signature()); return block;
        }
        if ("redacted_thinking".equals(type)) {
            JsonObject block = new JsonObject(); block.addProperty("type", type); block.addProperty("data", message.content()); return block;
        }
        JsonObject block = new JsonObject(); block.addProperty("type", "text"); block.addProperty("text", message.content()); return block;
    }
    @Override public AdapterResponse parseResponse(String text) {
        JsonArray blocks = new JsonParser().parse(text).getAsJsonObject().getAsJsonArray("content");
        StringBuilder content = new StringBuilder(), reasoning = new StringBuilder(); String signature = null;
        for (JsonElement element : blocks) {
            JsonObject block = element.getAsJsonObject(); String type = block.get("type").getAsString();
            if ("thinking".equals(type)) { reasoning.append(block.get("thinking").getAsString()); if (block.has("signature")) signature = block.get("signature").getAsString(); }
            else if ("text".equals(type)) content.append(block.get("text").getAsString());
        }
        return new AdapterResponse(content.toString(), reasoning.length() == 0 ? null : reasoning.toString(), null, signature);
    }
}

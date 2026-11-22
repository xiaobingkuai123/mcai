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
            if ("thinking".equals(m.blockType()) && (m.signature() == null || m.signature().isBlank())) continue;
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
            // Unsigned legacy reasoning cannot be replayed as an Anthropic thinking block.
            if ("thinking".equals(m.blockType()) && (m.signature() == null || m.signature().isBlank())) continue;
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
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    JsonObject block = blocks.get(i).getAsJsonObject();
                    if ("text".equals(block.get("type").getAsString())) { block.add("cache_control", cache); break; }
                }
            }
        }
        JsonObject body = new JsonObject(); body.addProperty("model", model); body.add("messages", a);
        if (systemBlocks.size() > 0) body.add("system", systemBlocks);
        body.addProperty("max_tokens", o.maxTokens()); body.addProperty("stream", o.streaming());
        configureThinking(body, model, o);
        if (apiVersion.compareTo("2026-10-21") < 0 && !o.thinkingEnabled()
                && "disabled".equals(body.getAsJsonObject("thinking").get("type").getAsString())
                && !model.matches("claude-(opus|sonnet)-[45].*") && !model.startsWith("claude-haiku-5-5")) {
            body.addProperty("temperature", o.temperature());
        }
        return HttpRequest.newBuilder().uri(URI.create(ApiUrls.v1(baseUrl, "/messages")))
                .header("Content-Type", "application/json").header("x-api-key", key).header("anthropic-version", apiVersion)
                .timeout(Duration.ofSeconds(o.timeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    public static void configureThinking(JsonObject body, String model, ApiRequestOptions o) {
        if ("reasoning".equals(o.display())) throw new IllegalArgumentException("Anthropic does not expose raw reasoning; use summary or none");
        if (!java.util.Set.of("none", "summary").contains(o.display())) throw new IllegalArgumentException("Invalid Anthropic display");
        String name = model.toLowerCase(java.util.Locale.ROOT);
        boolean adaptive = name.matches("claude-(opus|sonnet)-4-[678](-[0-9]{8}|-latest)?")
                || name.matches("claude-(opus|sonnet)-5(-5)?(-[0-9]{8}|-latest)?") || name.matches("claude-haiku-5-5(-[0-9]{8}|-latest)?");
        boolean legacy = name.matches("claude-(opus|sonnet|haiku)-4(-[15])?(-[0-9]{8})?")
                || name.matches("claude-3(-[57])?-(opus|sonnet|haiku)(-[0-9]{8}|-latest)?");
        if (!adaptive && !legacy) throw new IllegalArgumentException("Unknown Anthropic model thinking capabilities: " + model);
        boolean mandatoryAdaptive = name.matches("claude-(opus|sonnet)-5(-5)?(-[0-9]{8}|-latest)?") || name.matches("claude-haiku-5-5(-[0-9]{8}|-latest)?");
        boolean sonnet55 = name.matches("claude-sonnet-5-5(-[0-9]{8}|-latest)?");
        String effort = o.reasoningEffort() == null ? "medium" : o.reasoningEffort();
        if (!java.util.Set.of("low", "medium", "high").contains(effort))
            throw new IllegalArgumentException("Unsupported Anthropic effort: " + effort);
        if (o.thinkingEnabled() && name.startsWith("claude-3") && !name.matches("claude-3-7-sonnet(-[0-9]{8}|-latest)?"))
            throw new IllegalArgumentException("This Anthropic model does not support extended thinking: " + model);
        JsonObject t = new JsonObject();
        if (sonnet55 && (!o.thinkingEnabled() || "low".equals(effort))) {
            t.addProperty("type", "between_tools");
            JsonObject output = new JsonObject(); output.addProperty("effort", "low"); body.add("output_config", output);
        } else if (adaptive && (o.thinkingEnabled() || mandatoryAdaptive)) {
            t.addProperty("type", "adaptive"); t.addProperty("display", "none".equals(o.display()) ? "omitted" : "summarized");
            JsonObject output = new JsonObject(); output.addProperty("effort", o.thinkingEnabled() ? effort : "low"); body.add("output_config", output);
        } else if (o.thinkingEnabled()) {
            int budget = Math.min(o.thinkingBudget(), o.maxTokens() - 1);
            if (budget < 1024) throw new IllegalArgumentException("Anthropic thinking requires budget >= 1024 and max_tokens > budget");
            t.addProperty("type", "enabled"); t.addProperty("budget_tokens", budget);
            t.addProperty("display", "none".equals(o.display()) ? "omitted" : "summarized");
        } else t.addProperty("type", "disabled");
        body.add("thinking", t);
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
    @Override public AdapterResponse parseResponse(String text) throws cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException {
        try { return parsePayload(text); }
        catch (cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException e) { throw e; }
        catch (RuntimeException e) { throw new cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException("Invalid provider response fields", e); }
    }
    private AdapterResponse parsePayload(String text) throws cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException {
        JsonObject root = CompletionReasons.root(text);
        String reason = CompletionReasons.requiredString(root, "stop_reason");
        JsonArray blocks = CompletionReasons.requiredArray(root, "content");
        StringBuilder content = new StringBuilder(), reasoning = new StringBuilder(); String signature = null;
        java.util.ArrayList<Message> saved = new java.util.ArrayList<>();
        for (JsonElement element : blocks) {
            JsonObject block = element.getAsJsonObject(); String type = block.get("type").getAsString();
            if ("thinking".equals(type)) {
                String value = CompletionReasons.optionalString(block, "thinking");
                String sig = block.has("signature") ? block.get("signature").getAsString() : null;
                reasoning.append(value); if (signature == null) signature = sig;
                saved.add(new Message("assistant", value, type, sig));
            } else if ("text".equals(type)) {
                String value = block.get("text").getAsString(); content.append(value); saved.add(new Message("assistant", value));
            } else if ("redacted_thinking".equals(type)) saved.add(new Message("assistant", block.get("data").getAsString(), type));
        }
        return new AdapterResponse(content.toString(), reasoning.isEmpty() ? null : reasoning.toString(), CacheUsageParser.parse(root), signature, saved, CompletionReasons.anthropic(reason), reason, TokenUsageParser.parse(root));
    }
}

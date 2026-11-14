package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class DeepSeekAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public DeepSeekAdapter(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    @Override public HttpRequest buildRequest(String baseUrl, String key, String model, int max, List<Message> messages, boolean thinking) throws Exception { return buildRequest(baseUrl, key, model, messages, new ApiRequestOptions(.7, 1, max, timeoutSeconds, 0, false, thinking, 4096, "medium")); }
    @Override public HttpRequest buildRequest(String baseUrl, String key, String model, List<Message> messages, ApiRequestOptions o) {
        JsonArray a = new JsonArray(); for (Message m : messages) { JsonObject x = new JsonObject(); x.addProperty("role", m.role()); x.addProperty("content", m.content()); a.add(x); }
        JsonObject b = new JsonObject(); b.addProperty("model", model); b.add("messages", a); b.addProperty("max_tokens", o.maxTokens()); b.addProperty("stream", o.streaming());
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", o.thinkingEnabled() ? "enabled" : "disabled");
        b.add("thinking", thinking);
        if (o.thinkingEnabled() && o.reasoningEffort() != null && !o.reasoningEffort().isBlank()) {
            b.addProperty("reasoning_effort", o.reasoningEffort());
        }
        if (!o.thinkingEnabled()) { b.addProperty("temperature", o.temperature()); b.addProperty("top_p", o.topP()); }
        if (o.streaming()) { JsonObject usage = new JsonObject(); usage.addProperty("include_usage", true); b.add("stream_options", usage); }
        return HttpRequest.newBuilder().uri(URI.create(ApiUrls.v1(baseUrl, "/chat/completions"))).header("Content-Type", "application/json").header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(o.timeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();
    }
    @Override public AdapterResponse parseResponse(String text) { JsonObject m = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message"); String c=m.has("content")&&!m.get("content").isJsonNull()?m.get("content").getAsString():""; String r=m.has("reasoning_content")&&!m.get("reasoning_content").isJsonNull()?m.get("reasoning_content").getAsString():null; return new AdapterResponse(c,r); }
}

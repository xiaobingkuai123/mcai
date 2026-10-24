package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class AnthropicAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public AnthropicAdapter(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override
    public HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                                    List<Message> messages, boolean isThinking) throws Exception {
        JsonArray messageArray = new JsonArray();
        String systemText = null;
        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                if (systemText == null) systemText = msg.content();
            } else {
                JsonObject obj = new JsonObject();
                obj.addProperty("role", msg.role());
                obj.addProperty("content", msg.content());
                messageArray.add(obj);
            }
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messageArray);
        if (systemText != null) body.addProperty("system", systemText);
        if (isThinking) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("type", "enabled");
            int budget = Math.min(maxTokens - 500, 4096);
            if (budget < 1024) budget = 1024;
            thinkingConfig.addProperty("budget_tokens", budget);
            body.add("thinking", thinkingConfig);
            body.addProperty("max_tokens", Math.max(maxTokens, budget + 500));
        } else {
            body.addProperty("max_tokens", maxTokens);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(ApiUrls.v1(baseUrl, "/messages")))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    @Override
    public AdapterResponse parseResponse(String responseBody) throws Exception {
        JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
        JsonArray contentArray = root.getAsJsonArray("content");
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < contentArray.size(); i++) {
            JsonObject block = contentArray.get(i).getAsJsonObject();
            String type = block.get("type").getAsString();
            if ("thinking".equals(type)) {
                reasoning.append(block.get("thinking").getAsString());
            } else if ("text".equals(type)) {
                text.append(block.get("text").getAsString());
            }
        }
        return new AdapterResponse(text.toString(), reasoning.length() > 0 ? reasoning.toString() : null);
    }
}

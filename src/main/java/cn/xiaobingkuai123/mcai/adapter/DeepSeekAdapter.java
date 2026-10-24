package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class DeepSeekAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public DeepSeekAdapter(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override
    public HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                                    List<Message> messages, boolean isThinking) throws Exception {
        JsonArray messagesArray = new JsonArray();
        for (Message msg : messages) {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", msg.role());
            obj.addProperty("content", msg.content());
            messagesArray.add(obj);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messagesArray);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", false);
        if (isThinking) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("type", "enabled");
            body.add("thinking", thinkingConfig);
        } else {
            body.addProperty("temperature", 0.7);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(ApiUrls.v1(baseUrl, "/chat/completions")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    @Override
    public AdapterResponse parseResponse(String responseBody) throws Exception {
        JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
        JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message");
        String content = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        String reasoning = message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()
                ? message.get("reasoning_content").getAsString() : null;
        return new AdapterResponse(content, reasoning);
    }
}

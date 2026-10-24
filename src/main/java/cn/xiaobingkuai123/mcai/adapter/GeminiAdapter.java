package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class GeminiAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public GeminiAdapter(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override
    public HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                                    List<Message> messages, boolean isThinking) throws Exception {
        JsonObject systemInstruction = null;
        JsonArray contentsArray = new JsonArray();
        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                JsonObject part = new JsonObject();
                part.addProperty("text", msg.content());
                JsonArray parts = new JsonArray();
                parts.add(part);
                systemInstruction = new JsonObject();
                systemInstruction.add("parts", parts);
                continue;
            }
            JsonObject part = new JsonObject();
            part.addProperty("text", msg.content());
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject contentObj = new JsonObject();
            String role = "assistant".equals(msg.role()) ? "model" : "user";
            contentObj.addProperty("role", role);
            contentObj.add("parts", parts);
            contentsArray.add(contentObj);
        }
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", maxTokens);
        if (!isThinking) generationConfig.addProperty("temperature", 0.7);
        if (isThinking) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("includeThoughts", true);
            thinkingConfig.addProperty("thinkingBudget", Math.min(maxTokens, 4096));
            generationConfig.add("thinkingConfig", thinkingConfig);
        }
        JsonObject body = new JsonObject();
        if (systemInstruction != null) body.add("systemInstruction", systemInstruction);
        body.add("contents", contentsArray);
        body.add("generationConfig", generationConfig);
        String url = baseUrl.replaceAll("/$", "") + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    @Override
    public AdapterResponse parseResponse(String responseBody) throws Exception {
        JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
        JsonObject candidate = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject content = candidate.getAsJsonObject("content");
        JsonArray parts = content.getAsJsonArray("parts");
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            JsonObject part = parts.get(i).getAsJsonObject();
            boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
            if (thought && part.has("text")) {
                reasoning.append(part.get("text").getAsString());
            } else if (!thought && part.has("text")) {
                text.append(part.get("text").getAsString());
            }
        }
        return new AdapterResponse(text.toString(), reasoning.length() > 0 ? reasoning.toString() : null);
    }
}

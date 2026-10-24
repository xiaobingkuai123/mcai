package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class OpenAIResponsesAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public OpenAIResponsesAdapter(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override
    public HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                                    List<Message> messages, boolean isThinking) throws Exception {
        String endpoint = baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")
                ? baseUrl.replaceAll("/$", "") + "/responses"
                : baseUrl.replaceAll("/$", "") + "/v1/responses";

        JsonArray inputArray = new JsonArray();
        for (Message msg : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", msg.role());
            item.addProperty("content", msg.content());
            inputArray.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", inputArray);
        body.addProperty("max_output_tokens", maxTokens);
        if (isThinking) {
            JsonObject reasoningConfig = new JsonObject();
            reasoningConfig.addProperty("effort", "medium");
            reasoningConfig.addProperty("summary", "auto");
            body.add("reasoning", reasoningConfig);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    @Override
    public AdapterResponse parseResponse(String responseBody) throws Exception {
        JsonObject root = new JsonParser().parse(responseBody).getAsJsonObject();
        if (!root.has("output")) return new AdapterResponse("", null);
        JsonArray outputArray = root.getAsJsonArray("output");
        String reasoning = null;
        String content = "";
        for (int i = 0; i < outputArray.size(); i++) {
            JsonObject item = outputArray.get(i).getAsJsonObject();
            String type = item.has("type") ? item.get("type").getAsString() : "";
            if ("reasoning".equals(type)) {
                JsonArray summaries = item.getAsJsonArray("summary");
                if (summaries != null && summaries.size() > 0) {
                    reasoning = summaries.get(0).getAsJsonObject().get("text").getAsString();
                }
            } else if ("message".equals(type)) {
                JsonArray contentArray = item.getAsJsonArray("content");
                if (contentArray != null) {
                    for (int k = 0; k < contentArray.size(); k++) {
                        JsonObject block = contentArray.get(k).getAsJsonObject();
                        if (block.has("type") && "output_text".equals(block.get("type").getAsString())
                                && block.has("text")) {
                            content = block.get("text").getAsString();
                            break;
                        }
                    }
                }
            }
        }
        return new AdapterResponse(content, reasoning);
    }
}

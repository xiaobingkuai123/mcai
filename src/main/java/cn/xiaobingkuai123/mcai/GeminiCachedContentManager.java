package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Optional Gemini Cached Content resource manager. Failures fall back to inline history. */
public final class GeminiCachedContentManager {
    private record Entry(String hash, String resource) {}
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile HttpClient client;
    private final int timeoutSeconds;
    public GeminiCachedContentManager(HttpClient client, int timeoutSeconds) { this.client = client; this.timeoutSeconds = timeoutSeconds; }
    public void setClient(HttpClient client) { this.client = client; }

    public String getOrCreate(String baseUrl, String apiKey, String model, List<ApiAdapter.Message> prefix) {
        if (prefix.isEmpty()) return null;
        String key = model;
        String hash = hash(prefix);
        Entry old = entries.get(key);
        if (old != null && old.hash().equals(hash)) return old.resource();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model.startsWith("models/") ? model : "models/" + model);
            JsonArray contents = new JsonArray(); JsonObject system = null;
            for (ApiAdapter.Message message : prefix) {
                if ("system".equals(message.role())) {
                    JsonObject part = new JsonObject(); part.addProperty("text", message.content());
                    JsonArray parts = new JsonArray(); parts.add(part); system = new JsonObject(); system.add("parts", parts); continue;
                }
                JsonObject part = new JsonObject(); part.addProperty("text", message.content());
                JsonArray parts = new JsonArray(); parts.add(part); JsonObject content = new JsonObject();
                content.addProperty("role", "assistant".equals(message.role()) ? "model" : "user"); content.add("parts", parts); contents.add(content);
            }
            if (system != null) body.add("systemInstruction", system);
            body.add("contents", contents); body.addProperty("ttl", "3600s");
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$", "") + "/v1beta/cachedContents"))
                    .header("Content-Type", "application/json").header("x-goog-api-key", apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            String resource = result.has("name") ? result.get("name").getAsString() : null;
            if (resource != null && !resource.isBlank()) { entries.put(key, new Entry(hash, resource)); return resource; }
        } catch (Exception ignored) { }
        return null;
    }

    private static String hash(List<ApiAdapter.Message> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ApiAdapter.Message m : messages) digest.update((m.role() + "\0" + m.content() + "\0").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format("%02x", b)); return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

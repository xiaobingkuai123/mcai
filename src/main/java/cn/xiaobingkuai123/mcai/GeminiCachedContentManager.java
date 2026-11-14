package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded, single-flight Gemini cache. Failed creation always falls back to inline messages. */
public final class GeminiCachedContentManager implements AutoCloseable {
    private record Entry(String resource, long created, long lifetime) {}
    private final ConcurrentHashMap<String, CompletableFuture<Entry>> entries = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(256);
    private final LongSupplier clock;
    private final ScheduledExecutorService cleaner;
    private volatile HttpClient client;
    private volatile boolean closed;
    private final int timeoutSeconds;
    public GeminiCachedContentManager(HttpClient client, int timeoutSeconds) { this(client, timeoutSeconds, System::nanoTime); }
    GeminiCachedContentManager(HttpClient client, int timeoutSeconds, LongSupplier clock) {
        this.client = client; this.timeoutSeconds = timeoutSeconds; this.clock = clock;
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "McAI-gemini-cleanup"); t.setDaemon(true); return t; });
        cleaner.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }
    public void setClient(HttpClient client) { this.client = client; clear(); }
    public String getOrCreate(String baseUrl, String apiKey, String model, List<ApiAdapter.Message> prefix) {
        if (closed || prefix.isEmpty() || Thread.currentThread().isInterrupted()) return null;
        String normalized = baseUrl.replaceAll("/+$", "");
        String normalizedModel = model.startsWith("models/") ? model.substring(7) : model;
        String key = hash(List.of(normalized, apiKey, normalizedModel, new Gson().toJson(prefix)));
        cleanup();
        CompletableFuture<Entry> future = entries.get(key);
        if (future == null) {
            if (!capacity.tryAcquire()) return null;
            CompletableFuture<Entry> candidate = new CompletableFuture<>();
            future = entries.putIfAbsent(key, candidate);
            if (future != null) capacity.release();
            else {
                future = candidate;
                try { candidate.complete(create(normalized, apiKey, normalizedModel, prefix)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); candidate.complete(null); }
                catch (Exception e) { candidate.complete(null); }
                finally { if (closed || candidate.getNow(null) == null) remove(key, candidate); }
            }
        }
        try {
            Entry entry = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (entry == null || closed || expired(entry)) { remove(key, future); return null; }
            return entry.resource();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (ExecutionException | TimeoutException e) { return null; }
    }
    private Entry create(String baseUrl, String apiKey, String model, List<ApiAdapter.Message> prefix) throws Exception {
        if (closed) return null;
        long started = clock.getAsLong();
        JsonObject body = new JsonObject(); body.addProperty("model", "models/" + model);
        JsonArray contents = new JsonArray(), systems = new JsonArray();
        JsonObject previous = null;
        for (ApiAdapter.Message message : prefix) {
            JsonObject part = new JsonObject(); part.addProperty("text", message.content());
            if ("system".equals(message.role())) { systems.add(part); continue; }
            if ("gemini_thought".equals(message.blockType())) part.addProperty("thought", true);
            if (message.signature() != null) part.addProperty("thoughtSignature", message.signature());
            String role = "assistant".equals(message.role()) ? "model" : "user";
            if (previous == null || !"model".equals(role) || !message.blockType().startsWith("gemini_")
                    || !role.equals(previous.get("role").getAsString())) {
                previous = new JsonObject(); previous.addProperty("role", role);
                previous.add("parts", new JsonArray()); contents.add(previous);
            }
            previous.getAsJsonArray("parts").add(part);
        }
        if (!systems.isEmpty()) { JsonObject system = new JsonObject(); system.add("parts", systems); body.add("systemInstruction", system); }
        body.add("contents", contents); body.addProperty("ttl", "3600s");
        HttpClient current = client; if (current == null) return null;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/cachedContents"))
                .header("Content-Type", "application/json").header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = current.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        String resource = result.has("name") ? result.get("name").getAsString() : null;
        if (resource == null || !resource.matches("cachedContents/[A-Za-z0-9_.-]+")) return null;
        long lifetime = TimeUnit.SECONDS.toNanos(3595); // conservative margin, measured from request start
        if (result.has("expireTime")) {
            long remaining = Duration.between(Instant.now(), Instant.parse(result.get("expireTime").getAsString())).minusSeconds(5).toNanos();
            lifetime = Math.min(lifetime, remaining);
        }
        if (lifetime <= 0) return null;
        return new Entry(resource, started, lifetime);
    }
    private boolean expired(Entry entry) { return clock.getAsLong() - entry.created() >= entry.lifetime(); }
    void cleanup() {
        entries.forEach((key, future) -> { Entry entry = future.getNow(null); if (future.isDone() && (entry == null || expired(entry))) remove(key, future); });
    }
    public void invalidate(String resource) {
        entries.forEach((key, future) -> { Entry entry = future.getNow(null); if (entry != null && entry.resource().equals(resource)) remove(key, future); });
    }
    private void remove(String key, CompletableFuture<Entry> future) { if (entries.remove(key, future)) capacity.release(); }
    private void clear() { entries.forEach(this::remove); }
    int size() { return entries.size(); }
    @Override public void close() { closed = true; cleaner.shutdownNow(); clear(); client = null; }
    private static String hash(List<String> values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new Gson().toJson(values).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @FunctionalInterface interface CachedOperation<T> { T run(String resource) throws Exception; }
    <T> T withFallback(String resource, CachedOperation<T> operation, java.util.function.BooleanSupplier emitted) throws Exception {
        try { return operation.run(resource); }
        catch (AiStreamClient.ApiHttpException e) {
            if (resource == null || emitted.getAsBoolean() || (e.statusCode() != 400 && e.statusCode() != 403 && e.statusCode() != 404 && e.statusCode() != 410)) throw e;
            invalidate(resource);
            return operation.run(null);
        }
    }
}

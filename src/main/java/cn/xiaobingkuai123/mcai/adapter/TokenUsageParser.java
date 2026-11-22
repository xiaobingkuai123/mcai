package cn.xiaobingkuai123.mcai.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Provider usage snapshots, never increments. Missing counters remain null. */
public final class TokenUsageParser {
    private TokenUsageParser() {}
    public static ApiAdapter.TokenUsage parse(JsonObject root) {
        if (root == null) return null;
        JsonObject nested = object(root, "response");
        if (nested == null) nested = object(root, "message");
        JsonObject u = object(root, "usage");
        if (u == null && nested != null) u = object(nested, "usage");
        if (u == null) u = object(root, "usageMetadata");
        if (u == null) return null;
        String type = text(root, "type");
        boolean complete = type.isEmpty() || "message".equals(type) || java.util.Set.of("response.completed", "response.incomplete", "response.failed").contains(type) || u.has("prompt_tokens");
        Long in, out, total, cached, creation = null, reasoning;
        String source;
        if (u.has("prompt_tokens") || u.has("completion_tokens")) {
            source = "chat-completions"; in = number(u,"prompt_tokens"); out = number(u,"completion_tokens");
            total = number(u,"total_tokens"); cached = number(object(u,"prompt_tokens_details"),"cached_tokens");
            reasoning = number(object(u,"completion_tokens_details"),"reasoning_tokens");
        } else if (u.has("promptTokenCount") || u.has("candidatesTokenCount")) {
            source = "gemini"; in = number(u,"promptTokenCount"); out = number(u,"candidatesTokenCount");
            total = number(u,"totalTokenCount"); cached = number(u,"cachedContentTokenCount"); reasoning = number(u,"thoughtsTokenCount");
        } else if (object(root,"response") != null || root.has("status") || u.has("total_tokens") || u.has("input_tokens_details") || u.has("output_tokens_details")) {
            source = "openai-responses"; in = number(u,"input_tokens"); out = number(u,"output_tokens"); total = number(u,"total_tokens");
            cached = number(object(u,"input_tokens_details"),"cached_tokens"); reasoning = number(object(u,"output_tokens_details"),"reasoning_tokens");
        } else {
            source = "anthropic"; creation = number(u,"cache_creation_input_tokens"); cached = number(u,"cache_read_input_tokens");
            in = sum(number(u,"input_tokens"), creation, cached); out = number(u,"output_tokens");
            total = in == null || out == null ? null : sum(in,out); reasoning = null;
        }
        return new ApiAdapter.TokenUsage(in,out,total,cached,creation,reasoning,source,complete);
    }
    public static ApiAdapter.TokenUsage merge(ApiAdapter.TokenUsage previous, ApiAdapter.TokenUsage incoming) {
        if (incoming == null) return previous;
        if (previous == null) return incoming;
        // A final snapshot must never be replaced by an earlier partial event.
        if (previous.complete() && !incoming.complete()) return previous;
        Long in = pick(incoming.inputTokens(), previous.inputTokens()), out = pick(incoming.outputTokens(), previous.outputTokens());
        Long total = pick(incoming.totalTokens(), previous.totalTokens());
        if ("anthropic".equals(incoming.source())) {
            if (out != null && previous.outputTokens() != null) out = Math.max(out, previous.outputTokens());
            total = in == null || out == null ? null : sum(in,out);
        }
        return new ApiAdapter.TokenUsage(in,out,total,pick(incoming.cachedInputTokens(),previous.cachedInputTokens()),
                pick(incoming.cacheCreationInputTokens(),previous.cacheCreationInputTokens()),pick(incoming.reasoningTokens(),previous.reasoningTokens()),
                incoming.source() == null ? previous.source() : incoming.source(), previous.complete() || incoming.complete());
    }
    public static ApiAdapter.TokenUsage complete(ApiAdapter.TokenUsage u, boolean complete) {
        return u == null ? null : new ApiAdapter.TokenUsage(u.inputTokens(),u.outputTokens(),u.totalTokens(),u.cachedInputTokens(),u.cacheCreationInputTokens(),u.reasoningTokens(),u.source(),complete);
    }
    private static Long pick(Long value, Long fallback) { return value == null ? fallback : value; }
    public static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static Long sum(Long... values) { Long result = null; for (Long n : values) if (n != null) result = saturatedAdd(result == null ? 0 : result,n); return result; }
    private static Long number(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        java.math.BigInteger n = o.get(key).getAsBigInteger();
        if (n.signum() < 0) throw new IllegalArgumentException("Negative token usage: " + key);
        return n.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }
    private static JsonObject object(JsonObject o,String key) { JsonElement e = o == null ? null : o.get(key); return e != null && e.isJsonObject() ? e.getAsJsonObject() : null; }
    private static String text(JsonObject o,String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
}

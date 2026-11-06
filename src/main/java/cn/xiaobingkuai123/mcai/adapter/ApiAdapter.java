package cn.xiaobingkuai123.mcai.adapter;

import java.net.http.HttpRequest;
import java.util.List;

public interface ApiAdapter {
    /** Provider-neutral message plus optional provider block metadata. */
    record Message(String role, String content, String blockType, String signature) {
        public Message(String role, String content) { this(role, content, "text", null); }
        public Message(String role, String content, String blockType) { this(role, content, blockType, null); }
    }
    record CacheUsage(String field, long hitTokens, long missTokens) {
        public boolean reported() { return field != null; }
    }
    record AdapterResponse(String content, String reasoning, CacheUsage cacheUsage, String reasoningSignature) {
        public AdapterResponse(String content, String reasoning) { this(content, reasoning, null, null); }
        public AdapterResponse(String content, String reasoning, CacheUsage cacheUsage) { this(content, reasoning, cacheUsage, null); }
        public AdapterResponse(String content, String reasoning, Long cachedTokens) { this(content, reasoning, cachedTokens == null ? null : new CacheUsage("cached_tokens", cachedTokens, 0), null); }
        public Long cacheHitTokens() { return cacheUsage == null ? null : cacheUsage.hitTokens(); }
    }
    HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                             List<Message> messages, boolean isThinking) throws Exception;
    default HttpRequest buildRequest(String baseUrl, String apiKey, String model,
                                     List<Message> messages, ApiRequestOptions options) throws Exception {
        return buildRequest(baseUrl, apiKey, model, options.maxTokens(), messages, options.thinkingEnabled());
    }
    AdapterResponse parseResponse(String responseBody) throws Exception;
}

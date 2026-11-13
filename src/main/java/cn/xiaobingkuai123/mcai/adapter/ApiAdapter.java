package cn.xiaobingkuai123.mcai.adapter;

import java.net.http.HttpRequest;
import java.util.List;

public interface ApiAdapter {
    /** Provider-neutral message plus optional provider block metadata. */
    record Message(String role, String content, String blockType, String signature) {
        public Message(String role, String content) { this(role, content, "text", null); }
        public Message(String role, String content, String blockType) { this(role, content, blockType, null); }
    }
    record CacheUsage(String field, long hitTokens, long missTokens) {}
    record AdapterResponse(String content, String reasoning, CacheUsage cacheUsage, String reasoningSignature,
                           List<Message> contentBlocks) {
        public AdapterResponse { contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks); }
        public AdapterResponse(String content, String reasoning, CacheUsage cacheUsage, String reasoningSignature) {
            this(content, reasoning, cacheUsage, reasoningSignature, List.of());
        }
        public AdapterResponse(String content, String reasoning) { this(content, reasoning, null, null); }
        public AdapterResponse(String content, String reasoning, CacheUsage cacheUsage) { this(content, reasoning, cacheUsage, null); }
    }
    HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                             List<Message> messages, boolean isThinking) throws Exception;
    default HttpRequest buildRequest(String baseUrl, String apiKey, String model,
                                     List<Message> messages, ApiRequestOptions options) throws Exception {
        return buildRequest(baseUrl, apiKey, model, options.maxTokens(), messages, options.thinkingEnabled());
    }
    AdapterResponse parseResponse(String responseBody) throws Exception;
}

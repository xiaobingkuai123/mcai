package cn.xiaobingkuai123.mcai.adapter;

public record ApiRequestOptions(double temperature, double topP, int maxTokens, int timeoutSeconds,
                                int maxRetries, boolean streaming, boolean thinkingEnabled,
                                int thinkingBudget, String reasoningEffort, String cachedContent, boolean includeThoughts) {
    public ApiRequestOptions(double temperature, double topP, int maxTokens, int timeoutSeconds,
                             int maxRetries, boolean streaming, boolean thinkingEnabled,
                             int thinkingBudget, String reasoningEffort, String cachedContent) {
        this(temperature, topP, maxTokens, timeoutSeconds, maxRetries, streaming, thinkingEnabled,
                thinkingBudget, reasoningEffort, cachedContent, thinkingEnabled);
    }
    public ApiRequestOptions(double temperature, double topP, int maxTokens, int timeoutSeconds,
                             int maxRetries, boolean streaming, boolean thinkingEnabled,
                             int thinkingBudget, String reasoningEffort) {
        this(temperature, topP, maxTokens, timeoutSeconds, maxRetries, streaming, thinkingEnabled, thinkingBudget, reasoningEffort, null);
    }
    public static ApiRequestOptions defaults(int maxTokens, int timeoutSeconds, boolean thinking) {
        return new ApiRequestOptions(.7, 1.0, maxTokens, timeoutSeconds, 0, false, thinking, 4096, "medium", null);
    }
}

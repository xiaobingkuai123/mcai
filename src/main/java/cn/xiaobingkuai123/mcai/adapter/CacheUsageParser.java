package cn.xiaobingkuai123.mcai.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Reads optional provider usage metadata for both full responses and stream events. */
public final class CacheUsageParser {
    private CacheUsageParser() {}

    public static ApiAdapter.CacheUsage parse(JsonObject root) {
        try {
            JsonObject usage = usageObject(root);
            if (usage == null) return null;
            String detailsField = "prompt_tokens_details";
            String totalField = "prompt_tokens";
            JsonObject details = object(usage, detailsField);
            if (details == null) {
                detailsField = "input_tokens_details";
                totalField = "input_tokens";
                details = object(usage, detailsField);
            }
            if (details != null && present(details, "cached_tokens")) {
                long hit = number(details, "cached_tokens");
                return new ApiAdapter.CacheUsage(detailsField + ".cached_tokens", hit,
                        number(usage, totalField) - hit);
            }
            if (present(usage, "prompt_cache_hit_tokens")) {
                return new ApiAdapter.CacheUsage("prompt_cache_hit_tokens",
                        number(usage, "prompt_cache_hit_tokens"), number(usage, "prompt_cache_miss_tokens"));
            }
            if (present(usage, "cache_read_input_tokens")) {
                return new ApiAdapter.CacheUsage("cache_read_input_tokens",
                        number(usage, "cache_read_input_tokens"), number(usage, "cache_creation_input_tokens"));
            }
            if (present(usage, "cachedContentTokenCount")) {
                long hit = number(usage, "cachedContentTokenCount");
                return new ApiAdapter.CacheUsage("cachedContentTokenCount", hit,
                        number(usage, "promptTokenCount") - hit);
            }
        } catch (RuntimeException ignored) {
            // Optional accounting metadata must not discard an otherwise valid answer.
        }
        return null;
    }

    private static JsonObject usageObject(JsonObject root) {
        JsonObject usage = object(root, "usage");
        if (usage == null) usage = object(root, "usageMetadata");
        if (usage == null) {
            JsonObject nested = object(root, "response");
            if (nested == null) nested = object(root, "message");
            if (nested != null) {
                usage = object(nested, "usage");
                if (usage == null) usage = object(nested, "usageMetadata");
            }
        }
        return usage;
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static boolean present(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && !value.isJsonNull();
    }

    private static long number(JsonObject root, String key) {
        return present(root, key) ? root.get(key).getAsLong() : 0;
    }
}

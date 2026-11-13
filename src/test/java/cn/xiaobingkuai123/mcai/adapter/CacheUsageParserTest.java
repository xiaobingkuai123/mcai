package cn.xiaobingkuai123.mcai.adapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheUsageParserTest {
    private JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void providerFieldsHaveTheSameMeaningInResponsesAndStreamEnvelopes() {
        String[] payloads = {
            "{\"usage\":{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":60}}}",
            "{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":60}}}",
            "{\"usage\":{\"prompt_cache_hit_tokens\":60,\"prompt_cache_miss_tokens\":40}}",
            "{\"usage\":{\"cache_read_input_tokens\":60,\"cache_creation_input_tokens\":40}}",
            "{\"usageMetadata\":{\"cachedContentTokenCount\":60,\"promptTokenCount\":100}}"
        };
        for (String payload : payloads) {
            JsonObject root = json(payload);
            var usage = CacheUsageParser.parse(root);
            assertNotNull(usage);
            assertEquals(60, usage.hitTokens());
            assertEquals(40, usage.missTokens());
            for (String envelope : new String[]{"response", "message"}) {
                JsonObject event = new JsonObject();
                event.add(envelope, root);
                assertEquals(usage, CacheUsageParser.parse(event));
            }
        }
        assertEquals("input_tokens_details.cached_tokens", CacheUsageParser.parse(json(payloads[1])).field());
    }

    @Test void absentOrMalformedOptionalStatisticsDoNotDiscardTheAnswer() {
        assertNull(CacheUsageParser.parse(json("{}")));
        assertNull(CacheUsageParser.parse(json("{\"usage\":{\"prompt_cache_hit_tokens\":null}}")));
        assertNull(CacheUsageParser.parse(json("{\"usage\":{\"prompt_cache_hit_tokens\":\"invalid\"}}")));
    }
}

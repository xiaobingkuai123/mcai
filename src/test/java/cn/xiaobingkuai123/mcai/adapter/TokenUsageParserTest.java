package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TokenUsageParserTest {
    private ApiAdapter.TokenUsage parse(String s) { return TokenUsageParser.parse(JsonParser.parseString(s).getAsJsonObject()); }
    @Test void anthropicSnapshotsMergeWithoutAddingCumulativeOutput() {
        var u = parse("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":50,\"cache_creation_input_tokens\":100,\"cache_read_input_tokens\":200}}}");
        for(int n : new int[]{10,20,30}) u = TokenUsageParser.merge(u,parse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":"+n+"}}"));
        assertEquals(350L,u.inputTokens()); assertEquals(30L,u.outputTokens()); assertEquals(380L,u.totalTokens());
        assertEquals(200L,u.cachedInputTokens()); assertEquals(100L,u.cacheCreationInputTokens()); assertFalse(u.complete());
    }
    @Test void missingAndExplicitZeroDiffer() {
        assertNull(parse("{}")); assertNull(parse("{\"usage\":null}"));
        var u=parse("{\"usage\":{\"prompt_tokens\":0}}"); assertEquals(0L,u.inputTokens()); assertNull(u.outputTokens());
        assertEquals(u,TokenUsageParser.merge(u,null));
    }
    @Test void independentAnthropicInputsAndOverflow() {
        for(String field : new String[]{"input_tokens","cache_read_input_tokens","cache_creation_input_tokens"}) {
            var u=parse("{\"usage\":{\""+field+"\":7,\"output_tokens\":2}}"); assertEquals(7L,u.inputTokens()); assertEquals(9L,u.totalTokens());
        }
        var root=JsonParser.parseString("{\"usage\":{\"input_tokens\":9223372036854775807,\"cache_creation_input_tokens\":1,\"cache_read_input_tokens\":2}}").getAsJsonObject();
        assertEquals(Long.MAX_VALUE,TokenUsageParser.parse(root).inputTokens()); assertEquals(Long.MAX_VALUE,CacheUsageParser.parse(root).missTokens());
    }
    @Test void openAiFormatsAndNestedResponses() {
        var chat=parse("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":30},\"completion_tokens_details\":{\"reasoning_tokens\":10}}}");
        var responses=parse("{\"response\":{\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,\"input_tokens_details\":{\"cached_tokens\":30},\"output_tokens_details\":{\"reasoning_tokens\":10}}}}");
        assertEquals(chat.inputTokens(),responses.inputTokens()); assertEquals(10L,responses.reasoningTokens()); assertEquals(30L,responses.cachedInputTokens());
    }

    @Test void finalAnthropicMessageAndEarlierCumulativeSnapshot() {
        var u=parse("{\"type\":\"message\",\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}");
        assertTrue(u.complete()); assertEquals(70L,u.totalTokens());
        var partial=parse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":10}}");
        assertEquals(20L,TokenUsageParser.merge(u,partial).outputTokens());
        var later=parse("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":30}}");
        assertEquals(30L,TokenUsageParser.merge(later,partial).outputTokens());
        assertFalse(TokenUsageParser.complete(u,false).complete());
    }
    @Test void responsesWithoutOptionalDetailsStillHaveCorrectSource() {
        var u=parse("{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}");
        assertEquals("openai-responses",u.source()); assertNull(u.totalTokens()); assertEquals(1L,u.inputTokens());
    }
}

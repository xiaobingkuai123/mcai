package cn.xiaobingkuai123.mcai.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class AdapterRequestTest {
    private static JsonObject body(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var result = new CompletableFuture<byte[]>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] bytes = new byte[b.remaining()]; b.get(bytes); out.writeBytes(bytes); }
            public void onError(Throwable t) { result.completeExceptionally(t); }
            public void onComplete() { result.complete(out.toByteArray()); }
        });
        return JsonParser.parseString(new String(result.get(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Test void stableOpenAiRequestRetainsOrderedRolesAndStreaming() throws Exception {
        var options = new ApiRequestOptions(.25, .8, 100, 30, 0, true, false, 2048, "low");
        List<ApiAdapter.Message> messages = List.of(new ApiAdapter.Message("system", "s"), new ApiAdapter.Message("user", "u"), new ApiAdapter.Message("assistant", "a"), new ApiAdapter.Message("user", "latest"));
        JsonObject first = body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "m", messages, options));
        JsonArray a = first.getAsJsonArray("messages");
        assertEquals(List.of("system", "user", "assistant", "user"), java.util.stream.IntStream.range(0,a.size()).mapToObj(i -> a.get(i).getAsJsonObject().get("role").getAsString()).toList());
        assertEquals(List.of("s", "u", "a", "latest"), java.util.stream.IntStream.range(0, a.size())
                .mapToObj(i -> a.get(i).getAsJsonObject().get("content").getAsString()).toList());
        assertTrue(first.get("stream").getAsBoolean());
        assertEquals(.25, first.get("temperature").getAsDouble());
        assertEquals(.8, first.get("top_p").getAsDouble());
    }
    @Test void deepseekEnablesThinkingOnlyInThinkMode() throws Exception {
        var think = new ApiRequestOptions(.4, .9, 100, 30, 0, false, true, 2048, "high");
        JsonObject thinkBody = body(new DeepSeekAdapter(30).buildRequest("https://api.deepseek.com", "key", "m", List.of(), think));
        assertEquals("enabled", thinkBody.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(thinkBody.has("temperature"));

        var normal = new ApiRequestOptions(.4, .9, 100, 30, 0, false, false, 2048, "high");
        JsonObject normalBody = body(new DeepSeekAdapter(30).buildRequest("https://api.deepseek.com", "key", "m", List.of(), normal));
        assertEquals("disabled", normalBody.getAsJsonObject("thinking").get("type").getAsString());
        assertTrue(normalBody.has("temperature"));
    }

    @Test void openAiReasoningUsesCompletionTokenField() throws Exception {
        var options = new ApiRequestOptions(.4, .9, 100, 30, 0, false, true, 2048, "high");
        JsonObject request = body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "o3-mini", List.of(), options));
        assertTrue(request.has("max_completion_tokens"));
        assertFalse(request.has("max_tokens"));
        assertEquals("high", request.get("reasoning_effort").getAsString());
        assertFalse(request.has("temperature"));
    }

    @Test void openAiNormalModeKeepsStandardCompletionField() throws Exception {
        var options = new ApiRequestOptions(.4, .9, 100, 30, 0, false, false, 2048, "high");
        JsonObject request = body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "gpt-4o-mini", List.of(), options));
        assertTrue(request.has("max_tokens"));
        assertFalse(request.has("max_completion_tokens"));
        assertFalse(request.has("reasoning_effort"));
    }

    @Test void anthropicThinkingUsesEnabledBudget() throws Exception {
        var options = new ApiRequestOptions(.4, .9, 4096, 30, 0, false, true, 2048, "high");
        JsonObject request = body(new AnthropicAdapter(30, "2023-06-01").buildRequest("https://api.anthropic.com", "key", "claude-sonnet-4", List.of(new ApiAdapter.Message("system", "s")), options));
        assertEquals("enabled", request.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals(2048, request.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        assertEquals("2023-06-01", new AnthropicAdapter(30, "2023-06-01").buildRequest("https://api.anthropic.com", "key", "claude-sonnet-4", List.of(), options).headers().firstValue("anthropic-version").orElseThrow());
    }

    @Test void geminiDisablesThinkingInNormalMode() throws Exception {
        var options = new ApiRequestOptions(.4, .9, 100, 30, 0, false, false, 2048, "high");
        HttpRequest httpRequest = new GeminiAdapter(30).buildRequest("https://generativelanguage.googleapis.com", "key", "gemini-2.5-flash", List.of(), options);
        JsonObject request = body(httpRequest);
        assertEquals(0, request.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingBudget").getAsInt());
        assertEquals("key", httpRequest.headers().firstValue("x-goog-api-key").orElseThrow());
        assertFalse(httpRequest.uri().getQuery() != null && httpRequest.uri().getQuery().contains("key="));
    }

    @Test void anthropicThinkingReplayPreservesSignature() throws Exception {
        var messages = List.of(new ApiAdapter.Message("user", "u"), new ApiAdapter.Message("assistant", "thought", "thinking", "sig"), new ApiAdapter.Message("assistant", "answer"));
        var options = new ApiRequestOptions(.4, .9, 4096, 30, 0, false, true, 2048, "high");
        JsonObject request = body(new AnthropicAdapter(30).buildRequest("https://api.anthropic.com", "key", "claude-sonnet-4", messages, options));
        JsonArray content = request.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals(2, content.size());
        assertEquals("thinking", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("sig", content.get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("text", content.get(1).getAsJsonObject().get("type").getAsString());
    }
    @Test void responsesSummaryUsesExactAllowListAndLevel() throws Exception {
        var options = new ApiRequestOptions(.4, .9, 100, 30, 0, false, true, 2048, "high");
        var adapter = new OpenAIResponsesAdapter(30, true, java.util.Set.of("gpt-6.1-sol"), "detailed");
        JsonObject supported = body(adapter.buildRequest("https://api.openai.com", "key", "gpt-6.1-sol", List.of(), options));
        assertEquals("detailed", supported.getAsJsonObject("reasoning").get("summary").getAsString());
        JsonObject unsupported = body(adapter.buildRequest("https://api.openai.com", "key", "gpt-6.1-solution", List.of(), options));
        assertFalse(unsupported.getAsJsonObject("reasoning").has("summary"));
        var normal = new ApiRequestOptions(.4, .9, 100, 30, 0, false, false, 2048, "high");
        assertFalse(body(adapter.buildRequest("https://api.openai.com", "key", "gpt-6.1-sol", List.of(), normal)).has("reasoning"));
        assertFalse(body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "gpt-6.1-sol", List.of(), options)).has("summary"));
    }

    @Test void responsesNonStreamingCombinesAllOfficialSummaryParts() throws Exception {
        var response = new OpenAIResponsesAdapter(30).parseResponse("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"secret\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"one\"},{\"type\":\"summary_text\",\"text\":\"two\"}]},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}]}");
        assertEquals("onetwo", response.reasoning()); assertEquals("answer", response.content());
    }

    @Test void anthropicThinkingRequestsSummarizedDisplay() throws Exception {
        var options = new ApiRequestOptions(.4, .9, 4096, 30, 0, false, true, 2048, "high");
        JsonObject request = body(new AnthropicAdapter(30).buildRequest("https://api.anthropic.com", "key", "claude-sonnet-4", List.of(), options));
        assertEquals("summarized", request.getAsJsonObject("thinking").get("display").getAsString());
    }

    @Test void publicThinkingOptionsReachProviderWithoutPrivateDefaults() throws Exception {
        var adapter = new OpenAIResponsesAdapter(30, true, java.util.Set.of("public-model"), "auto");
        var messages = List.of(new ApiAdapter.Message("user", "question"));
        var on = body(adapter.buildRequest("https://example.test", "key", "public-model", messages,
                new ApiRequestOptions(.7,1,8192,30,0,true,true,2048,"high")));
        assertEquals("public-model", on.get("model").getAsString());
        assertEquals("high", on.getAsJsonObject("reasoning").get("effort").getAsString());
        assertEquals("auto", on.getAsJsonObject("reasoning").get("summary").getAsString());
        var off = body(adapter.buildRequest("https://example.test", "key", "public-model", messages,
                new ApiRequestOptions(.7,1,8192,30,0,true,false,0,null)));
        assertFalse(off.has("reasoning"));
        var anthropic = body(new AnthropicAdapter(30).buildRequest("https://example.test", "key", "claude-sonnet-4", messages,
                new ApiRequestOptions(.7,1,8192,30,0,true,true,2048,"high")));
        assertEquals(2048,anthropic.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        assertEquals("summarized",anthropic.getAsJsonObject("thinking").get("display").getAsString());
    }


    @Test void deepSeekUsesChatReasoningAndEffortWithoutAnySummaryParameter() throws Exception {
        var adapter = new DeepSeekAdapter(30);
        var request = adapter.buildRequest("https://api.deepseek.com", "key", "deepseek-flash", List.of(),
                new ApiRequestOptions(.7, 1, 8192, 30, 0, true, true, 2048, "high"));
        assertEquals("/chat/completions", request.uri().getPath());
        var json = body(request);
        assertEquals("enabled", json.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("high", json.get("reasoning_effort").getAsString());
        assertFalse(json.has("reasoning")); assertFalse(json.toString().contains("summary"));
        assertTrue(json.get("stream").getAsBoolean());
    }

    @Test void geminiThoughtOutputAndInternalBudgetRemainIndependentWithoutSummaryWhitelist() throws Exception {
        for (String model : List.of("gemini-2.5-flash", "gateway-future-thinking-model")) {
            for (boolean include : List.of(true, false)) {
                var options = new ApiRequestOptions(.7, 1, 8192, 30, 0, true, true, 2048, "high", null, include);
                var request = new GeminiAdapter(30).buildRequest("https://generativelanguage.googleapis.com", "key", model, List.of(), options);
                assertTrue(request.uri().getPath().endsWith(":generateContent"));
                var thinking = body(request).getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig");
                assertEquals(include, thinking.get("includeThoughts").getAsBoolean());
                assertEquals(2048, thinking.get("thinkingBudget").getAsInt());
                assertFalse(thinking.has("thinkingLevel"));
            }
        }
    }

    @Test void geminiLevelModelsSendLevelAlongsideIndependentIncludeThoughts() throws Exception {
        for (String model : List.of("gemini-3-flash-preview", "models/gemini-3-pro-preview")) {
            for (boolean include : List.of(true, false)) {
                var options = new ApiRequestOptions(.7, 1, 8192, 30, 0, true, true, 2048, "low", null, include);
                var json = body(new GeminiAdapter(30).buildRequest("https://example.test", "key", model, List.of(), options));
                var thinking = json.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig");
                assertEquals("low", thinking.get("thinkingLevel").getAsString());
                assertEquals(include, thinking.get("includeThoughts").getAsBoolean());
                assertFalse(thinking.has("thinkingBudget"));
            }
        }
    }

    @Test void geminiPrivateLegacyOptionsStillRequestThoughtsAndEmptyThoughtsDoNotHideAnswers() throws Exception {
        var adapter = new GeminiAdapter(30);
        var json = body(adapter.buildRequest("https://example.test", "key", "gemini-2.5-flash", List.of(),
                new ApiRequestOptions(.7, 1, 8192, 30, 0, true, true, 4096, "medium")));
        assertTrue(json.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("includeThoughts").getAsBoolean());
        var response = adapter.parseResponse("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"\"},{\"text\":\"one\"},{\"thought\":false,\"text\":\"two\"},{\"thoughtSignature\":\"secret\"}]}}]}");
        assertNull(response.reasoning()); assertEquals("onetwo", response.content());
        assertEquals("secret", response.contentBlocks().get(3).signature());
    }

    @Test void deepSeekUrlsPreserveExplicitPrefixes() throws Exception {
        for (String base : List.of("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/v1", "https://api.deepseek.com/v1/", "https://proxy.test/prefix/v1///")) {
            assertEquals(base.replaceAll("/+$", "") + "/chat/completions", ApiUrls.deepSeekChatCompletions(base));
            var request = new DeepSeekAdapter(30).buildRequest(base,"key","deepseek-flash",List.of(),new ApiRequestOptions(.7,1,4096,30,0,true,true,2048,"high"));
            assertFalse(request.uri().getPath().contains("/v1/v1/"));
            assertEquals("deepseek-flash",body(request).get("model").getAsString());
            assertTrue(body(request).getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
        }
        assertEquals("https://api.openai.com/v1/chat/completions",ApiUrls.v1("https://api.openai.com/","/chat/completions"));
    }
    @Test void adaptiveCapabilitiesAndDisplayAreValidated() throws Exception {
        for(String model : List.of("claude-opus-4-6","claude-opus-4-7","claude-opus-4-8","claude-opus-5","claude-opus-5-5","claude-sonnet-5","claude-haiku-5-5")) {
            var options=new ApiRequestOptions(.2,.3,8192,30,0,false,true,2048,"high",null,true,"none");
            var request=new AnthropicAdapter(30,"2026-10-21").buildRequest("https://api.anthropic.com","key",model,List.of(),options);
            var b=body(request); assertEquals("adaptive",b.getAsJsonObject("thinking").get("type").getAsString());
            assertEquals("omitted",b.getAsJsonObject("thinking").get("display").getAsString());
            assertFalse(b.has("temperature")); assertFalse(b.has("top_p")); assertFalse(b.has("top_k"));
            assertEquals("2026-10-21",request.headers().firstValue("anthropic-version").orElseThrow());
        }
        for(boolean enabled:List.of(true,false)) {
            var b=body(new AnthropicAdapter(30).buildRequest("https://api.anthropic.com","key","claude-sonnet-5-5",List.of(),new ApiRequestOptions(.7,1,8192,30,0,false,enabled,2048,"low")));
            var t=b.getAsJsonObject("thinking"); assertEquals("between_tools",t.get("type").getAsString()); assertFalse(t.has("display")); assertFalse(t.has("budget_tokens"));
        }
        var off=body(new AnthropicAdapter(30).buildRequest("https://api.anthropic.com","key","claude-opus-5-5",8192,List.of(),false));
        assertEquals("adaptive",off.getAsJsonObject("thinking").get("type").getAsString());
        assertThrows(IllegalArgumentException.class,()->new AnthropicAdapter(30).buildRequest("https://api.anthropic.com","key","unknown",8192,List.of(),true));
        assertThrows(IllegalArgumentException.class,()->new AnthropicAdapter(30).buildRequest("https://api.anthropic.com","key","claude-opus-5-5",List.of(),new ApiRequestOptions(.7,1,8192,30,0,false,true,2048,"high",null,true,"reasoning")));
    }
}

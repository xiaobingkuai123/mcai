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
        return new JsonParser().parse(new String(result.get(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Test void systemPromptRemainsFirstBeforeConversationHistory() throws Exception {
        var options = new ApiRequestOptions(.25, .8, 100, 30, 0, false, false, 2048, "low");
        var messages = List.of(new ApiAdapter.Message("system", "stable system"), new ApiAdapter.Message("user", "latest history"), new ApiAdapter.Message("assistant", "history answer"), new ApiAdapter.Message("user", "current"));
        JsonArray serialized = body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "m", messages, options)).getAsJsonArray("messages");
        assertEquals("system", serialized.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("stable system", serialized.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("latest history", serialized.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test void stableOpenAiRequestRetainsOrderedRolesAndStreaming() throws Exception {
        var options = new ApiRequestOptions(.25, .8, 100, 30, 0, true, false, 2048, "low");
        List<ApiAdapter.Message> messages = List.of(new ApiAdapter.Message("system", "s"), new ApiAdapter.Message("user", "u"), new ApiAdapter.Message("assistant", "a"), new ApiAdapter.Message("user", "latest"));
        JsonObject first = body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "m", messages, options));
        JsonObject second = body(new OpenAIAdapter(30).buildRequest("https://api.openai.com", "key", "m", messages, options));
        assertEquals(first, second);
        JsonArray a = first.getAsJsonArray("messages");
        assertEquals(List.of("system", "user", "assistant", "user"), java.util.stream.IntStream.range(0,a.size()).mapToObj(i -> a.get(i).getAsJsonObject().get("role").getAsString()).toList());
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
}

package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchFixRegressionTest {
    @Test void slidingWindowExpiresIndividualRequestsAndCleansIdlePlayers() {
        AtomicLong now = new AtomicLong();
        try (var limiter = new RequestRateLimiter(now::get)) {
            UUID id = UUID.randomUUID();
            assertTrue(limiter.acquire(id, 2));
            now.set(TimeUnit.SECONDS.toNanos(30)); assertTrue(limiter.acquire(id, 2));
            now.set(TimeUnit.SECONDS.toNanos(60)-1); assertFalse(limiter.acquire(id, 2));
            now.incrementAndGet(); assertTrue(limiter.acquire(id, 2)); assertFalse(limiter.acquire(id, 2));
            assertTrue(limiter.acquire(UUID.randomUUID(), 1));
            now.set(TimeUnit.SECONDS.toNanos(120)); limiter.cleanup(); assertEquals(0, limiter.trackedPlayers());
            assertFalse(limiter.acquire(id, 0)); assertEquals(0, limiter.trackedPlayers());
        }
    }
    @Test void closedLimiterRejectsNewRequestsAndReleasesState() {
        var limiter = new RequestRateLimiter();
        limiter.close();
        assertFalse(limiter.acquire(UUID.randomUUID(), 1));
        assertEquals(0, limiter.trackedPlayers());
    }
    @Test void monotonicClockHandlesNanoTimeWrap() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE-10);
        try (var limiter = new RequestRateLimiter(now::get)) {
            UUID id=UUID.randomUUID(); assertTrue(limiter.acquire(id,1));
            now.addAndGet(RequestRateLimiter.WINDOW_NANOS); assertTrue(limiter.acquire(id,1));
        }
    }
    @Test void logsRedactCredentials() {
        String safe=ChatManager.redactCredentials("Authorization: Bearer secret api_key=other token=hidden sk-ant-example AIzaExample {\"Authorization\":\"Bearer jsonSecret\",\"api_key\": \"jsonKey\",\"access_token\":\"jsonToken\"}");
        for(String secret:List.of("secret","other","hidden","sk-ant-example","AIzaExample","jsonSecret","jsonKey","jsonToken")) assertFalse(safe.contains(secret));
    }
    @Test void retirementCallbackIsExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger(); Runnable retired=FoliaScheduler.once(calls::incrementAndGet);
        try (var pool=Executors.newFixedThreadPool(8)) {
            var tasks=java.util.stream.IntStream.range(0,100).<Callable<Void>>mapToObj(i -> () -> { retired.run(); return null; }).toList();
            for(var result:pool.invokeAll(tasks)) result.get();
        }
        assertEquals(1,calls.get());
    }
    private static String event(String json) { return "data: " + json + "\n\n"; }
    private static String block(int index, String type, String value, String sig) {
        return event("{\"type\":\"content_block_start\",\"index\":"+index+",\"content_block\":{\"type\":\""+type+"\",\""+(type.equals("thinking")?"thinking":type.equals("text")?"text":"data")+"\":\""+value+"\",\"signature\":\""+sig+"\"}}");
    }
    private static String stop(int i) { return event("{\"type\":\"content_block_stop\",\"index\":"+i+"}"); }
    @Test void anthropicStreamPreservesIndexedBlocksAndSignatureFragmentsThroughReplay() throws Exception {
        String stream=block(0,"thinking","a","")
            +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"b\"}}")
            +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}")
            +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"1\"}}")+stop(0)
            +block(1,"text","hello","")+stop(1)+block(2,"thinking","c","sig2")+stop(2)
            +block(3,"redacted_thinking","opaque","")+stop(3)+block(4,"text","world","")+stop(4)
            +event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}") + event("{\"type\":\"message_stop\"}");
        List<AiStreamClient.Delta> visible = new ArrayList<>();
        var response=sendStream(stream, visible::add);
        assertEquals("abc", visible.stream().map(AiStreamClient.Delta::reasoning).collect(java.util.stream.Collectors.joining()));
        assertEquals("helloworld", visible.stream().map(AiStreamClient.Delta::content).collect(java.util.stream.Collectors.joining()));
        assertEquals("helloworld",response.content()); assertEquals("abc",response.reasoning());
        assertEquals("sig1",response.reasoningSignature()); assertEquals(5,response.contentBlocks().size());
        var history=new ConversationHistory(ConversationHistory.Mode.SLIDING,5);
        var round=new ArrayList<ApiAdapter.Message>(); round.add(new ApiAdapter.Message("user","question")); round.addAll(response.contentBlocks()); history.addRound(round);
        var loaded=ConversationHistory.fromMessages(ConversationHistory.Mode.SLIDING,5,history.persistedMessages());
        assertEquals(1,loaded.roundCount()); assertEquals(round,loaded.promptMessages());
        assertEquals("sig2",loaded.promptMessages().get(3).signature());
        var request=new AnthropicAdapter(5).buildRequest("http://localhost","key","claude-sonnet-4",1000,loaded.promptMessages(),false);
        assertTrue(body(request).contains("sig1")); assertTrue(body(request).contains("sig2")); assertTrue(body(request).contains("opaque"));
    }
    static String body(HttpRequest request) throws Exception {
        var subscriber=new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            final java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            final CompletableFuture<String> result=new CompletableFuture<>();
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);out.writeBytes(a);}
            public void onError(Throwable t){result.completeExceptionally(t);}
            public void onComplete(){result.complete(out.toString(StandardCharsets.UTF_8));}
        };
        request.bodyPublisher().orElseThrow().subscribe(subscriber); return subscriber.result.get(5,TimeUnit.SECONDS);
    }
    private ApiAdapter.AdapterResponse sendStream(String stream) throws Exception {
        return sendStream(stream, d -> {});
    }
    private ApiAdapter.AdapterResponse sendStream(String stream, java.util.function.Consumer<AiStreamClient.Delta> listener) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] b=stream.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,b.length);try(var out=e.getResponseBody()){out.write(b);}});server.start();
        try(var client=HttpClient.newHttpClient()) {
            return AiStreamClient.send(client,new AnthropicAdapter(5),HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).timeout(java.time.Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),listener);
        } finally {server.stop(0);}
    }
    @Test void anthropicDoesNotCompleteAtBlockStopOrAcceptUnclosedBlocks() {
        assertThrows(java.io.IOException.class,()->sendStream(block(0,"thinking","a","sig")+stop(0)));
        assertThrows(AiStreamClient.ProtocolException.class,()->sendStream(block(0,"text","a","")+event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}") + event("{\"type\":\"message_stop\"}")));
        assertThrows(AiStreamClient.ProtocolException.class,()->sendStream(event("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"x\"}}")));
    }
    @Test void nonStreamingAnthropicRetainsEverySignature() throws Exception {
        var response=new AnthropicAdapter(5).parseResponse("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"a\",\"signature\":\"s1\"},{\"type\":\"thinking\",\"thinking\":\"b\",\"signature\":\"s2\"},{\"type\":\"text\",\"text\":\"ok\"}]}");
        assertEquals("s1",response.contentBlocks().get(0).signature()); assertEquals("s2",response.contentBlocks().get(1).signature());
    }
    @Test void responsesStreamSeparatesSummaryAndAnswerAndUsesDoneFallbackOnce() throws Exception {
        String stream = event("{\"type\":\"response.reasoning_summary_part.added\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}")
                + event("{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":0,\"delta\":\"one\"}")
                + event("{\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":0,\"text\":\"one\"}")
                + event("{\"type\":\"response.reasoning_summary_part.done\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"one\"}}")
                + event("{\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":1,\"text\":\"two\"}")
                + event("{\"type\":\"response.reasoning_summary_part.done\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":2,\"part\":{\"type\":\"summary_text\",\"text\":\"three\"}}")
                + event("{\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":2,\"text\":\"three\"}")
                + event("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"hidden\"}")
                + event("{\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}")
                + event("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        List<AiStreamClient.Delta> deltas = new ArrayList<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] b=stream.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,b.length);try(var out=e.getResponseBody()){out.write(b);}}); server.start();
        try(var client=HttpClient.newHttpClient()) {
            var response=AiStreamClient.send(client,new OpenAIResponsesAdapter(5,true,Set.of("m"),"auto"),HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/responses")).timeout(java.time.Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),deltas::add);
            assertEquals("onetwothree", response.reasoning()); assertEquals("answer", response.content());
            assertEquals(List.of("one","two","three"), deltas.stream().map(AiStreamClient.Delta::reasoning).filter(x->!x.isEmpty()).toList());
            assertEquals(List.of("answer"), deltas.stream().map(AiStreamClient.Delta::content).filter(x->!x.isEmpty()).toList());
        } finally { server.stop(0); }
    }


    private static ApiAdapter.AdapterResponse wire(ApiAdapter adapter,String events) throws Exception {
        return ProviderThinkingFlowTest.stream(adapter,events,d -> {});
    }
    @Test void chatTerminalMatrixKeepsUsageAfterGeneration() throws Exception {
        for(ApiAdapter adapter:List.of(new OpenAIAdapter(5),new DeepSeekAdapter(5))) {
            for(var entry:Map.of("stop",ApiAdapter.CompletionStatus.COMPLETED,"length",ApiAdapter.CompletionStatus.INCOMPLETE,"content_filter",ApiAdapter.CompletionStatus.BLOCKED,"tool_calls",ApiAdapter.CompletionStatus.UNSUPPORTED,"future",ApiAdapter.CompletionStatus.UNSUPPORTED).entrySet()) {
                String data=event("{\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}")
                    +event("{\"choices\":[{\"delta\":{},\"finish_reason\":\""+entry.getKey()+"\"}]}")
                    +event("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}}")+event("[DONE]");
                var r=wire(adapter,data); assertEquals("Hello",r.content()); assertEquals(entry.getValue(),r.completionStatus());
                assertEquals(entry.getKey(),r.finishReason()); assertEquals(100L,r.tokenUsage().inputTokens()); assertEquals(50L,r.tokenUsage().outputTokens()); assertEquals(150L,r.tokenUsage().totalTokens()); assertTrue(r.tokenUsage().complete());
            }
        }
    }
    @Test void missingTerminalAndMissingUsageAreDifferent() throws Exception {
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new OpenAIAdapter(5),event("[DONE]")));
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new OpenAIAdapter(5),event("{\"choices\":[]}")));
        var r=wire(new OpenAIAdapter(5),ProviderThinkingFlowTest.chat("{\"content\":\"ok\"}",true)+event("{\"choices\":[],\"usage\":null}")+event("[DONE]"));
        assertEquals(ApiAdapter.CompletionStatus.COMPLETED,r.completionStatus()); assertNull(r.tokenUsage());
        assertThrows(AiStreamClient.ProtocolException.class,()->sendStream(event("{\"type\":\"message_stop\"}")));
    }
    @Test void anthropicStopMatrixAndCumulativeUsage() throws Exception {
        for(var e:Map.of("end_turn",ApiAdapter.CompletionStatus.COMPLETED,"max_tokens",ApiAdapter.CompletionStatus.INCOMPLETE,"refusal",ApiAdapter.CompletionStatus.REFUSED,"model_context_window_exceeded",ApiAdapter.CompletionStatus.INCOMPLETE,"pause_turn",ApiAdapter.CompletionStatus.PAUSED,"tool_use",ApiAdapter.CompletionStatus.UNSUPPORTED).entrySet()) {
            String start=event("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":50,\"cache_creation_input_tokens\":100,\"cache_read_input_tokens\":200}}}");
            for(int n:new int[]{10,20,30}) start+=event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":null},\"usage\":{\"output_tokens\":"+n+"}}");
            var r=wire(new AnthropicAdapter(5),start+block(0,"text","ok","")+stop(0)+event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""+e.getKey()+"\"}}")+event("{\"type\":\"message_stop\"}"));
            assertEquals(e.getValue(),r.completionStatus()); assertEquals(350L,r.tokenUsage().inputTokens()); assertEquals(30L,r.tokenUsage().outputTokens()); assertEquals(380L,r.tokenUsage().totalTokens()); assertEquals(200L,r.cacheUsage().hitTokens()); assertEquals(150L,r.cacheUsage().missTokens()); assertTrue(r.tokenUsage().complete());
        }
    }
    @Test void geminiStatesFeedbackAndProtocolFailures() throws Exception {
        for(var e:Map.of("STOP",ApiAdapter.CompletionStatus.COMPLETED,"MAX_TOKENS",ApiAdapter.CompletionStatus.INCOMPLETE,"SAFETY",ApiAdapter.CompletionStatus.BLOCKED,"OTHER",ApiAdapter.CompletionStatus.UNSUPPORTED).entrySet()) {
            String json="{\"candidates\":[{\"finishReason\":\""+e.getKey()+"\",\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}";
            assertEquals(e.getValue(),wire(new GeminiAdapter(5),event(json)).completionStatus()); assertEquals(e.getValue(),new GeminiAdapter(5).parseResponse(json).completionStatus());
        }
        String blocked="{\"promptFeedback\":{\"blockReason\":\"SAFETY\",\"blockReasonMessage\":\"blocked\"}}";
        assertEquals(ApiAdapter.CompletionStatus.BLOCKED,new GeminiAdapter(5).parseResponse(blocked).completionStatus());
        assertEquals(ApiAdapter.CompletionStatus.BLOCKED,wire(new GeminiAdapter(5),event(blocked)).completionStatus());
        assertThrows(AiStreamClient.ProtocolException.class,()->new GeminiAdapter(5).parseResponse("{\"candidates\":[]}"));
        assertThrows(AiStreamClient.ProtocolException.class,()->new DeepSeekAdapter(5).parseResponse("{\"choices\":[]}"));
        assertThrows(AiStreamClient.ProtocolException.class,()->new OpenAIAdapter(5).parseResponse("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
    }
    @Test void refusalAndResponsesIncompleteRemainDistinct() throws Exception {
        var chat=new OpenAIAdapter(5).parseResponse("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"declined\"}}]}");
        assertEquals(ApiAdapter.CompletionStatus.REFUSED,chat.completionStatus()); assertEquals("declined",chat.content()); assertDoesNotThrow(()->ChatManager.requireAccepted(chat));
        String data=event("{\"type\":\"response.refusal.delta\",\"delta\":\"declined\"}")+event("{\"type\":\"response.refusal.done\",\"refusal\":\"declined\"}")+event("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        var refused=wire(new OpenAIResponsesAdapter(5),data); assertEquals("declined",refused.content()); assertNull(refused.reasoning()); assertEquals(ApiAdapter.CompletionStatus.REFUSED,refused.completionStatus());
        var r=wire(new OpenAIResponsesAdapter(5),event("{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}"));
        assertEquals(ApiAdapter.CompletionStatus.INCOMPLETE,r.completionStatus()); assertEquals("max_output_tokens",r.finishReason()); assertThrows(ChatManager.ResponseStatusException.class,()->ChatManager.requireAccepted(r));
    }

    @Test void finishReasonWithoutDoneStillFailsBeforeUsageArrival() {
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new OpenAIAdapter(5),ProviderThinkingFlowTest.chat("{\"content\":\"partial\"}",true)));
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new DeepSeekAdapter(5),ProviderThinkingFlowTest.chat("{\"content\":\"partial\"}",true)));
    }
    @Test void anthropicStreamingAndNonStreamingUsageAgree() throws Exception {
        var a=new AnthropicAdapter(5);
        var full=a.parseResponse("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":50,\"cache_creation_input_tokens\":100,\"cache_read_input_tokens\":200,\"output_tokens\":20}}");
        var streamed=wire(a,event("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":50,\"cache_creation_input_tokens\":100,\"cache_read_input_tokens\":200}}}")
            +block(0,"text","ok","")+stop(0)+event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}")+event("{\"type\":\"message_stop\"}"));
        assertEquals(full.tokenUsage(),streamed.tokenUsage()); assertEquals(370L,streamed.tokenUsage().totalTokens()); assertEquals(full.cacheUsage(),streamed.cacheUsage());
    }
    @Test void responsesInternalStatusMustMatchTerminalEvent() {
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new OpenAIResponsesAdapter(5),event("{\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}")));
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new OpenAIResponsesAdapter(5),event("{\"type\":\"response.completed\"}")));
    }

    @Test void malformedRequiredFieldsAreProtocolErrorsAcrossAdapters() {
        assertThrows(AiStreamClient.ProtocolException.class,()->new AnthropicAdapter(5).parseResponse("{\"stop_reason\":\"end_turn\",\"content\":[false]}"));
        assertThrows(AiStreamClient.ProtocolException.class,()->new GeminiAdapter(5).parseResponse("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":false}]}"));
        assertThrows(AiStreamClient.ProtocolException.class,()->new OpenAIResponsesAdapter(5).parseResponse("{\"status\":\"completed\",\"output\":[false]}"));
        assertThrows(AiStreamClient.ProtocolException.class,()->wire(new OpenAIAdapter(5),event("{\"choices\":[{\"delta\":{},\"finish_reason\":123}]}")+event("[DONE]")));
    }

    @Test void cacheLogsAreBilingualAndRespectLiveConfigurationSwitch() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        List<String> logs = new ArrayList<>();
        ChatManager.logCacheUsage(config, new ApiAdapter.CacheUsage("cached_tokens", 30, 70), logs::add);
        ChatManager.logCacheUsage(config, new ApiAdapter.CacheUsage("cached_tokens", 0, 100), logs::add);
        ChatManager.logCacheUsage(config, null, logs::add);
        assertEquals(3, logs.size());
        assertTrue(logs.get(0).contains("命中 / hit"));
        assertTrue(logs.get(0).contains("命中 Token / hit=30"));
        assertTrue(logs.get(0).contains("未命中 Token / miss=70"));
        assertTrue(logs.get(1).contains("未命中 / miss"));
        assertTrue(logs.get(2).contains("未报告 / not-reported"));
        config.set("ai.adapters.request.log-cache-usage", false);
        ChatManager.logCacheUsage(config, new ApiAdapter.CacheUsage("cached_tokens", 30, 70), logs::add);
        ChatManager.logCacheUsage(config, null, logs::add);
        assertEquals(3, logs.size());
        config.set("ai.adapters.request.log-cache-usage", true);
        ChatManager.logCacheUsage(config, null, logs::add);
        assertEquals(4, logs.size());
    }
}

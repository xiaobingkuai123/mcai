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
            +event("{\"type\":\"message_stop\"}");
        var response=sendStream(stream);
        assertEquals("helloworld",response.content()); assertEquals("abc",response.reasoning());
        assertEquals("sig1",response.reasoningSignature()); assertEquals(5,response.contentBlocks().size());
        var history=new ConversationHistory(ConversationHistory.Mode.SLIDING,5);
        var round=new ArrayList<ApiAdapter.Message>(); round.add(new ApiAdapter.Message("user","question")); round.addAll(response.contentBlocks()); history.addRound(round);
        var loaded=ConversationHistory.fromMessages(ConversationHistory.Mode.SLIDING,5,history.persistedMessages());
        assertEquals(1,loaded.roundCount()); assertEquals(round,loaded.promptMessages());
        assertEquals("sig2",loaded.promptMessages().get(3).signature());
        var request=new AnthropicAdapter(5).buildRequest("http://localhost","key","model",1000,loaded.promptMessages(),false);
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
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] b=stream.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,b.length);try(var out=e.getResponseBody()){out.write(b);}});server.start();
        try(var client=HttpClient.newHttpClient()) {
            return AiStreamClient.send(client,new AnthropicAdapter(5),HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).timeout(java.time.Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),d->{});
        } finally {server.stop(0);}
    }
    @Test void anthropicDoesNotCompleteAtBlockStopOrAcceptUnclosedBlocks() {
        assertThrows(java.io.IOException.class,()->sendStream(block(0,"thinking","a","sig")+stop(0)));
        assertThrows(AiStreamClient.ProtocolException.class,()->sendStream(block(0,"text","a","")+event("{\"type\":\"message_stop\"}")));
        assertThrows(AiStreamClient.ProtocolException.class,()->sendStream(event("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"x\"}}")));
    }
    @Test void nonStreamingAnthropicRetainsEverySignature() {
        var response=new AnthropicAdapter(5).parseResponse("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"a\",\"signature\":\"s1\"},{\"type\":\"thinking\",\"thinking\":\"b\",\"signature\":\"s2\"},{\"type\":\"text\",\"text\":\"ok\"}]}");
        assertEquals("s1",response.contentBlocks().get(0).signature()); assertEquals("s2",response.contentBlocks().get(1).signature());
    }
}

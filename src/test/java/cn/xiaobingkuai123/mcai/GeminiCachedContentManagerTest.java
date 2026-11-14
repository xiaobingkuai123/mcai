package cn.xiaobingkuai123.mcai;
import cn.xiaobingkuai123.mcai.adapter.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class GeminiCachedContentManagerTest {
    @Test void expiresDeduplicatesSeparatesConfigurationsAndFallsBack() throws Exception {
        AtomicLong now=new AtomicLong(); AtomicInteger creates=new AtomicInteger(); AtomicBoolean fail=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{int n=creates.incrementAndGet();byte[] b=("{\"name\":\"cachedContents/cache"+n+"\"}").getBytes();e.sendResponseHeaders(fail.get()?500:200,b.length);try(var out=e.getResponseBody()){out.write(b);}});server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        var prefix=List.of(new ApiAdapter.Message("system","prompt"),new ApiAdapter.Message("user","hello"),new ApiAdapter.Message("assistant","world"));
        try(var client=HttpClient.newHttpClient(); var manager=new GeminiCachedContentManager(client,5,now::get)) {
            try(var pool=Executors.newFixedThreadPool(12)) {
                var tasks=java.util.stream.IntStream.range(0,50).<Callable<String>>mapToObj(i->()->manager.getOrCreate(url,"key","m",prefix)).toList();
                for(var f:pool.invokeAll(tasks)) assertEquals("cachedContents/cache1",f.get());
            }
            assertEquals(1,creates.get());
            manager.getOrCreate(url,"other","m",prefix);manager.getOrCreate(url,"key","other-model",prefix);manager.getOrCreate(url+"/other","key","m",prefix);
            assertEquals(4,creates.get());
            now.set(TimeUnit.SECONDS.toNanos(3595));manager.cleanup();assertEquals(0,manager.size());
            String resource=manager.getOrCreate(url,"key","m",prefix);assertEquals(5,creates.get());
            var attempts=new ArrayList<String>();
            assertEquals("inline",manager.withFallback(resource,r->{attempts.add(r);if(r!=null)throw new AiStreamClient.ApiHttpException(404);return "inline";},()->false));
            assertEquals(Arrays.asList(resource,null),attempts);assertEquals(0,manager.size());
            assertThrows(AiStreamClient.ApiHttpException.class,()->manager.withFallback(resource,r->{throw new AiStreamClient.ApiHttpException(404);},()->true));
            fail.set(true);assertNull(manager.getOrCreate(url,"key","m",prefix));assertEquals(0,manager.size());
            var adapter=new GeminiAdapter(5);
            var messages=new ArrayList<>(prefix);messages.add(new ApiAdapter.Message("user","new"));
            String inline=BatchFixRegressionTest.body(adapter.buildRequest(url,"key","m",messages,ApiRequestOptions.defaults(100,5,false)));
            assertTrue(inline.contains("prompt"));assertTrue(inline.contains("hello"));assertTrue(inline.contains("world"));assertFalse(inline.contains("cachedContent"));
        } finally {server.stop(0);}
    }
    @Test void closePreventsFurtherCreation() {
        var manager=new GeminiCachedContentManager(null,1);manager.close();assertNull(manager.getOrCreate("http://localhost","key","m",List.of(new ApiAdapter.Message("system","s"))));assertEquals(0,manager.size());
    }

    @Test void cachedGeminiHistoryPreservesThoughtPartsAndOpaqueSignatureAttachment() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = "{\"name\":\"cachedContents/signed\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try (var client = HttpClient.newHttpClient(); var manager = new GeminiCachedContentManager(client, 5)) {
            var prefix = List.of(new ApiAdapter.Message("user", "q"),
                    new ApiAdapter.Message("assistant", "summary", "gemini_thought", null),
                    new ApiAdapter.Message("assistant", "answer", "gemini_text", "opaque"));
            assertEquals("cachedContents/signed", manager.getOrCreate("http://127.0.0.1:" + server.getAddress().getPort(), "key", "m", prefix));
            var json = com.google.gson.JsonParser.parseString(captured.get()).getAsJsonObject();
            var parts = json.getAsJsonArray("contents").get(1).getAsJsonObject().getAsJsonArray("parts");
            assertEquals(2, parts.size());
            assertTrue(parts.get(0).getAsJsonObject().get("thought").getAsBoolean());
            assertEquals("opaque", parts.get(1).getAsJsonObject().get("thoughtSignature").getAsString());
            assertEquals("answer", parts.get(1).getAsJsonObject().get("text").getAsString());
        } finally { server.stop(0); }
    }
}

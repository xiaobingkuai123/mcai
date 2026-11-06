package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.OpenAIAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiRequestRetryTest {
    private HttpServer server;
    private HttpClient client;
    private HttpRequest request;
    private final AtomicInteger calls = new AtomicInteger();
    private final ApiRequestRetry retry = new ApiRequestRetry(() -> true, millis -> {});
    private static final String DELTA = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";
    private static final String DONE = "data: [DONE]\n\n";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        client = HttpClient.newHttpClient();
        request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
    }
    @AfterEach void stop() { server.stop(0); client.close(); }

    private void serve(int failures, int status, String body, boolean streaming) {
        server.createContext("/", exchange -> {
            int code = calls.incrementAndGet() <= failures ? status : 200;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    @Test void retriesNonStreamingServerErrors() throws Exception {
        serve(2, 503, "{}", false);
        assertEquals(200, retry.send(client, request, 2).statusCode());
        assertEquals(3, calls.get());
    }
    @Test void zeroBudgetAndExhaustionStopAtConfiguredAttempt() {
        serve(100, 429, "{}", false);
        assertThrows(AiStreamClient.ApiHttpException.class, () -> retry.send(client, request, 0));
        assertEquals(1, calls.get());
        assertThrows(AiStreamClient.ApiHttpException.class, () -> retry.send(client, request, 2));
        assertEquals(4, calls.get());
    }
    @Test void ordinaryClientErrorIsNotRetried() throws Exception {
        serve(100, 401, "{}", false);
        assertEquals(401, retry.send(client, request, 3).statusCode());
        assertEquals(1, calls.get());
    }
    @Test void streamingRetriesBeforeOutputAndIgnoresHeartbeats() throws Exception {
        serve(1, 429, ": keepalive\n\ndata: ping\n\ndata: heartbeat\n\n" + DELTA + DONE, true);
        AtomicInteger deltas = new AtomicInteger();
        var response = retry.stream(client, new OpenAIAdapter(30), request, delta -> deltas.incrementAndGet(), 2);
        assertEquals("hello", response.content());
        assertEquals(1, deltas.get());
        assertEquals(2, calls.get());
    }
    @Test void streamingRetriesServerErrorsBeforeOutput() throws Exception {
        serve(1, 503, DONE, true);
        retry.stream(client, new OpenAIAdapter(30), request, delta -> {}, 1);
        assertEquals(2, calls.get());
    }
    @Test void streamingClientErrorDoesNotRetry() {
        serve(100, 401, DONE, true);
        assertThrows(AiStreamClient.ApiHttpException.class,
                () -> retry.stream(client, new OpenAIAdapter(30), request, delta -> {}, 2));
        assertEquals(1, calls.get());
    }
    @Test void streamDisconnectAfterOutputNeverReplays() {
        serve(0, 200, DELTA, true);
        assertThrows(IOException.class, () -> retry.stream(client, new OpenAIAdapter(30), request, delta -> {}, 3));
        assertEquals(1, calls.get());
    }
    @Test void malformedJsonRemainsFailure() {
        serve(0, 200, "data: {broken\n\n" + DONE, true);
        assertThrows(AiStreamClient.ProtocolException.class,
                () -> retry.stream(client, new OpenAIAdapter(30), request, delta -> {}, 2));
        assertEquals(1, calls.get());
    }
    @Test void errorEventCannotBeIgnoredAsHeartbeat() {
        serve(0, 200, "event: error\ndata: overloaded\n\n" + DONE, true);
        assertThrows(AiStreamClient.ProtocolException.class,
                () -> retry.stream(client, new OpenAIAdapter(30), request, delta -> {}, 2));
        assertEquals(1, calls.get());
    }
    @Test void transportFailureRetriesButCancellationStopsBackoff() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        assertEquals("ok", retry.execute(() -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("connection reset");
            return "ok";
        }, 1, () -> false));
        AtomicBoolean active = new AtomicBoolean(true);
        var cancelling = new ApiRequestRetry(active::get, millis -> active.set(false));
        assertThrows(CancellationException.class, () -> cancelling.execute(() -> {
            throw new IOException("disconnected");
        }, 3, () -> false));
    }
    @Test void interruptionIsPreserved() {
        var interrupted = new ApiRequestRetry(() -> true, millis -> { throw new InterruptedException(); });
        try {
            assertThrows(InterruptedException.class, () -> interrupted.execute(() -> {
                throw new IOException("disconnected");
            }, 1, () -> false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}

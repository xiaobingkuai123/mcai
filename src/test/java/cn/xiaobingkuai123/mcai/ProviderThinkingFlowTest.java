package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.*;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Real wire payloads pass through HTTP/SSE parsing and the production public event router. */
class ProviderThinkingFlowTest {
    static String event(String json) { return "data: " + json + "\n\n"; }
    static String gemini(String parts, boolean done) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + parts
                + "]}" + (done ? ",\"finishReason\":\"STOP\"" : "") + "}]}";
    }
    static String chat(String delta, boolean done) {
        return event("{\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + (done ? "\"stop\"" : "null") + "}]}");
    }
    static ChatManager.ApiRequestProfile profile(ApiAdapter adapter) {
        return new ChatManager.ApiRequestProfile("openai", adapter, "test-key", "http://localhost", "m", 8192,
                "system", true, "effort", 2048, "high", .7, 1.0, 5, 0, true, false, true, "summary");
    }
    static ApiAdapter.AdapterResponse stream(ApiAdapter adapter, String data, Consumer<AiStreamClient.Delta> listener) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (adapter instanceof GeminiAdapter) {
                assertTrue(exchange.getRequestURI().toString().contains(":streamGenerateContent?alt=sse"));
            }
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String path = adapter instanceof GeminiAdapter ? "/v1beta/models/m:generateContent" : "/";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
            return AiStreamClient.send(client, adapter, request, listener);
        } finally { server.stop(0); }
    }
    static class PublicFlow {
        final PlayerStreamOutputTest.Scheduler scheduler = new PlayerStreamOutputTest.Scheduler();
        final PublicBossBarsTest.Recipient recipient = new PublicBossBarsTest.Recipient();
        final List<AiStreamClient.Delta> events = new ArrayList<>();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final ChatManager.ApiRequestProfile profile;
        final PublicAiManager.PublicBossBars bars = new PublicAiManager.PublicBossBars(scheduler, valid::get, k -> k, null);
        String thinkingTitle, answerTitle;
        PublicFlow(ApiAdapter adapter) { this(adapter, "summary"); }
        PublicFlow(ApiAdapter adapter, String display) {
            profile = "summary".equals(display) ? profile(adapter) : new ChatManager.ApiRequestProfile(
                    "deepseek", adapter, "test-key", "http://localhost", "m", 8192,
                    "system", true, "effort", 2048, "high", .7, 1.0, 5, 0, true, false, false, display);
        }
        void accept(AiStreamClient.Delta delta) {
            events.add(delta);
            PublicAiManager.acceptPublicDelta(profile, bars, delta);
            bars.refresh(List.of(recipient.player)); scheduler.run();
            String title = title();
            if (delta.type() == AiStreamClient.EventType.FINAL_ANSWER) answerTitle = title;
            else if (answerTitle == null) thinkingTitle = title;
            else assertEquals(answerTitle, title, "late thoughts cannot replace an answer");
            assertTrue(recipient.chat.isEmpty(), "no incremental text in public chat");
        }
        String title() { return ((TextComponent) ((BossBar) recipient.shown.iterator().next()).name()).content(); }
        void finish(ApiAdapter.AdapterResponse response) {
            bars.close(); scheduler.run(); assertTrue(recipient.shown.isEmpty());
            PublicAiManager.deliverResult(scheduler, valid::get, null, null, response.content(), recipient.chat::add);
            assertEquals(List.of(response.content()), recipient.chat);
        }
    }
    @Test void openAiOfficialSummaryReachesPublicBarAndPrivateOutputAndRawTextIsIgnored() throws Exception {
        var adapter = new OpenAIResponsesAdapter(5, true, Set.of("m"), "auto");
        var flow = new PublicFlow(adapter);
        var privatePlayer = new PlayerStreamOutputTest.Fixture();
        String data = event("{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":0,\"delta\":\"summary\"}")
                + event("{\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"r\",\"output_index\":0,\"summary_index\":0,\"text\":\"summary\"}")
                + event("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"RAW-SECRET\"}")
                + event("{\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}")
                + event("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        var response = stream(adapter, data, d -> { flow.accept(d); privatePlayer.output.accept(d); });
        assertEquals("summary", response.reasoning()); assertEquals("answer", response.content());
        assertEquals(AiStreamClient.EventType.OPENAI_REASONING_SUMMARY, flow.events.get(0).type());
        assertTrue(flow.thinkingTitle.contains("summary")); assertTrue(flow.answerTitle.contains("answer"));
        privatePlayer.output.finish(response.content(), response.reasoning()); privatePlayer.scheduler.run();
        assertEquals(List.of("reasoning-summary-label", "summary", "answer-label", "answer"), privatePlayer.messages);
        flow.finish(response);
    }
    @Test void deepSeekRawReasoningReachesBossBarWithoutSummaryAndFinalAnswerCompletes() throws Exception {
        var adapter = new DeepSeekAdapter(5); var flow = new PublicFlow(adapter, "reasoning");
        String data = chat("{\"reasoning_content\":\"raw one\",\"content\":null}", false)
                + chat("{\"reasoning_content\":\" raw two\",\"content\":\"answer\"}", false)
                + chat("{\"reasoning_content\":\"late raw\"}", false) + chat("{\"content\":\" done\"}", true);
        var response = stream(adapter, data, flow::accept);
        assertEquals("raw one raw twolate raw", response.reasoning()); assertEquals("answer done", response.content());
        assertTrue(flow.events.stream().filter(d -> !d.reasoning().isEmpty()).allMatch(d -> d.type() == AiStreamClient.EventType.DEEPSEEK_REASONING));
        assertTrue(flow.thinkingTitle.contains("raw one raw two"));
        assertFalse(PublicAiManager.thinkingEventAllowed(flow.profile, AiStreamClient.EventType.OPENAI_REASONING_SUMMARY));
        flow.finish(response);
        var json = adapter.parseResponse("{\"choices\":[{\"message\":{\"reasoning_content\":\"raw\",\"content\":\"answer\"}}]}");
        assertEquals("raw", json.reasoning()); assertEquals("answer", json.content());
    }
    @Test void geminiMultipleMixedPartsStreamSeparatelyAndPreserveSignatureWithoutDisplayingIt() throws Exception {
        var adapter = new GeminiAdapter(5); var flow = new PublicFlow(adapter);
        String first = "{\"thought\":true,\"text\":\"thought one\"},{\"thought\":true,\"text\":\" two\"}";
        String second = "{\"thought\":false,\"text\":\"answer\"},{\"thought\":true,\"text\":\"late thought\"},"
                + "{\"text\":\" done\",\"thoughtSignature\":\"OPAQUE-SECRET\"},{\"thoughtSignature\":\"ONLY-SIGNATURE\"}";
        var response = stream(adapter, event(gemini(first, false)) + event(gemini(second, true)), flow::accept);
        assertEquals("thought one twolate thought", response.reasoning()); assertEquals("answer done", response.content());
        assertTrue(flow.thinkingTitle.contains("thought one two")); assertFalse(flow.answerTitle.contains("SECRET"));
        assertTrue(flow.events.stream().noneMatch(d -> (d.content() + d.reasoning()).contains("SIGNATURE")));
        assertEquals(AiStreamClient.EventType.GEMINI_THOUGHT_SUMMARY, flow.events.get(0).type());
        assertEquals("OPAQUE-SECRET", response.contentBlocks().get(4).signature());
        assertEquals("ONLY-SIGNATURE", response.contentBlocks().get(5).signature());
        flow.finish(response);
        var parsed = adapter.parseResponse(gemini(first + "," + second, true));
        assertEquals(response.reasoning(), parsed.reasoning()); assertEquals(response.content(), parsed.content());
        assertEquals(response.contentBlocks(), parsed.contentBlocks());
        // Private/shared replay keeps opaque signatures in parts rather than visible text.
        var history = new ConversationHistory(ConversationHistory.Mode.SLIDING, 5);
        var round = new ArrayList<ApiAdapter.Message>(); round.add(new ApiAdapter.Message("user", "q")); round.addAll(parsed.contentBlocks());
        history.addRound(round);
        var loaded = ConversationHistory.fromMessages(ConversationHistory.Mode.SLIDING, 5, history.persistedMessages());
        JsonObject replay = JsonParser.parseString(BatchFixRegressionTest.body(adapter.buildRequest("http://localhost", "key", "gemini-2.5-flash", 8192, loaded.promptMessages(), true))).getAsJsonObject();
        JsonArray parts = replay.getAsJsonArray("contents").get(1).getAsJsonObject().getAsJsonArray("parts");
        assertEquals(6, parts.size()); assertEquals("OPAQUE-SECRET", parts.get(4).getAsJsonObject().get("thoughtSignature").getAsString());
        assertEquals("answer done", parsed.content());
    }
    @Test void noThoughtsAndAnswerFirstStillCompleteForEveryProvider() throws Exception {
        for (ApiAdapter adapter : List.of(new DeepSeekAdapter(5), new GeminiAdapter(5), new OpenAIResponsesAdapter(5))) {
            var flow = new PublicFlow(adapter);
            String data;
            if (adapter instanceof GeminiAdapter) data = event(gemini("{\"thought\":true,\"text\":\"\"},{\"text\":\"answer\"}", true));
            else if (adapter instanceof DeepSeekAdapter) data = chat("{\"content\":\"answer\"}", true);
            else data = event("{\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}") + event("{\"type\":\"response.completed\"}");
            var response = stream(adapter, data, flow::accept);
            assertNull(response.reasoning()); assertEquals("answer", response.content()); flow.finish(response);
        }
        var flow = new PublicFlow(new GeminiAdapter(5));
        var response = stream(flow.profile.adapter(), event(gemini("{\"text\":\"answer\"},{\"thought\":true,\"text\":\"late\"},{\"text\":\" done\"}", true)), flow::accept);
        assertEquals("answer done", response.content()); assertEquals("late", response.reasoning()); flow.finish(response);
    }
    @Test void mismatchedProviderAndRawEventsCannotBypassPublicFilterAndClosedBarsStayClosed() {
        var flow = new PublicFlow(new DeepSeekAdapter(5));
        PublicAiManager.acceptPublicDelta(flow.profile, flow.bars, new AiStreamClient.Delta("wrong", "", AiStreamClient.EventType.OPENAI_REASONING_SUMMARY));
        flow.bars.refresh(List.of(flow.recipient.player)); flow.scheduler.run(); assertFalse(flow.title().contains("wrong"));
        PublicAiManager.acceptPublicDelta(flow.profile, flow.bars, new AiStreamClient.Delta("raw", ""));
        flow.bars.refresh(List.of(flow.recipient.player)); flow.scheduler.run(); assertFalse(flow.title().contains("raw"));
        flow.bars.close(); flow.scheduler.run();
        PublicAiManager.acceptPublicDelta(flow.profile, flow.bars, new AiStreamClient.Delta("", "late", AiStreamClient.EventType.FINAL_ANSWER));
        flow.bars.refresh(List.of(flow.recipient.player)); flow.scheduler.run(); assertTrue(flow.recipient.shown.isEmpty());
    }
    @Test void consoleGetsCurrentResultWithoutPlayersOrChatLoggingAndStaleQueuedDeliveryIsSuppressed() {
        var scheduler = new PlayerStreamOutputTest.Scheduler(); var messages = new ArrayList<String>();
        var broadcast = new ArrayList<String>(); var valid = new AtomicBoolean(true);
        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(), new Class<?>[]{ConsoleCommandSender.class}, (p,m,a) -> {
            if (m.getName().equals("sendMessage")) { messages.add((String)a[0]); return null; }
            throw new AssertionError("Console delivery must not access player state: " + m.getName());
        });
        PublicAiManager.deliverResult(scheduler, valid::get, console, "summary", "answer", broadcast::add);
        scheduler.run(); assertEquals(List.of("answer"), messages); assertEquals(List.of("summary", "answer"), broadcast);
        PublicAiManager.deliverResult(scheduler, valid::get, console, null, "stale", broadcast::add);
        valid.set(false); scheduler.run(); assertEquals(List.of("answer"), messages);
        int before = broadcast.size();
        PublicAiManager.deliverResult(scheduler, valid::get, console, null, "already stale", broadcast::add);
        scheduler.run(); assertEquals(before, broadcast.size()); assertEquals(List.of("answer"), messages);
    }

    @Test void lateThinkingCannotReplaceAnAnswerForAnyOfTheThreeProtocols() throws Exception {
        for (ApiAdapter adapter : List.of(new OpenAIResponsesAdapter(5), new DeepSeekAdapter(5), new GeminiAdapter(5))) {
            var flow = new PublicFlow(adapter);
            String data;
            if (adapter instanceof GeminiAdapter) data = event(gemini("{\"text\":\"answer\"}", false))
                    + event(gemini("{\"thought\":true,\"text\":\"late\"},{\"text\":\" done\"}", true));
            else if (adapter instanceof DeepSeekAdapter) data = chat("{\"content\":\"answer\"}", false)
                    + chat("{\"reasoning_content\":\"late\"}", false) + chat("{\"content\":\" done\"}", true);
            else data = event("{\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}")
                    + event("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"late\"}")
                    + event("{\"type\":\"response.output_text.delta\",\"delta\":\" done\"}")
                    + event("{\"type\":\"response.completed\"}");
            var response = stream(adapter, data, flow::accept);
            assertFalse(flow.answerTitle.contains("late")); assertEquals("answer done", response.content());
            flow.finish(response);
        }
    }
}

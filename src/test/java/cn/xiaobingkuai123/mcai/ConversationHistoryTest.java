package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {
    private static List<ApiAdapter.Message> round(String n) { return List.of(new ApiAdapter.Message("user", "u" + n), new ApiAdapter.Message("assistant", "a" + n)); }
    @Test void slidingNeverPinsAnOldConversationRound() {
        var h = new ConversationHistory(ConversationHistory.Mode.SLIDING, 2);
        for (int i = 1; i <= 5; i++) h.addRound(round(String.valueOf(i)));
        assertEquals(List.of("u4", "a4", "u5", "a5"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList());
    }
    @Test void accumulateRejectsAnotherRoundAfterLimitAndCanReset() {
        var h = new ConversationHistory(ConversationHistory.Mode.ACCUMULATE, 1);
        assertTrue(h.canAddRound());
        h.addRound(round("1"));
        assertFalse(h.canAddRound());
        h.addRound(round("2"));
        assertTrue(h.capped());
        assertEquals(List.of("u1", "a1"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList());
        assertEquals(1, h.roundCount());
        h.reset();
        assertTrue(h.canAddRound());
    }
    @Test void incompleteRoundRejected() { var h = new ConversationHistory(ConversationHistory.Mode.SLIDING, 2); assertThrows(IllegalArgumentException.class, () -> h.addRound(List.of(new ApiAdapter.Message("user", "u")))); }
    @Test void anthropicThinkingBlockDoesNotEndRoundEarly() {
        var h = ConversationHistory.fromMessages(ConversationHistory.Mode.SLIDING, 2, List.of(
                new ConversationHistory.Message("user", "u", 1),
                new ConversationHistory.Message("assistant", "thought", 2, "thinking", "sig"),
                new ConversationHistory.Message("assistant", "answer", 3, "text", null)));
        assertEquals(1, h.roundCount());
        assertEquals(List.of("u", "thought", "answer"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList());
    }

    @Test void onlyAcceptedResponsesCanBecomeCompleteRounds() throws Exception {
        var h=new ConversationHistory(ConversationHistory.Mode.SLIDING,3);
        for(var status:List.of(ApiAdapter.CompletionStatus.INCOMPLETE,ApiAdapter.CompletionStatus.BLOCKED,ApiAdapter.CompletionStatus.FAILED,ApiAdapter.CompletionStatus.UNSUPPORTED,ApiAdapter.CompletionStatus.PAUSED)) {
            var r=new ApiAdapter.AdapterResponse("partial",null,null,null,List.of(),status,"reason",null);
            assertThrows(ChatManager.ResponseStatusException.class,()->ChatManager.requireAccepted(r)); assertEquals(0,h.roundCount());
        }
        var refused=new ApiAdapter.AdapterResponse("declined",null,null,null,List.of(),ApiAdapter.CompletionStatus.REFUSED,"refusal",null);
        ChatManager.requireAccepted(refused); h.addRound(List.of(new ApiAdapter.Message("user","q"),new ApiAdapter.Message("assistant",ChatManager.visibleContent(refused))));
        assertEquals("declined",h.promptMessages().get(1).content());
    }
    @Test void opaqueProviderSignaturesRoundTripExactly() {
        var h=new ConversationHistory(ConversationHistory.Mode.SLIDING,3);
        var blocks=List.of(new ApiAdapter.Message("user","q"),new ApiAdapter.Message("assistant","","thinking","sig+/=opaque"),new ApiAdapter.Message("assistant","answer","gemini_text","thought+/=opaque"));
        h.addRound(blocks); assertEquals(blocks,ConversationHistory.fromMessages(ConversationHistory.Mode.SLIDING,3,h.persistedMessages()).promptMessages());
    }

    private static McAI isolatedPlugin() throws Exception {
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var plugin=(McAI)((sun.misc.Unsafe)field.get(null)).allocateInstance(McAI.class);
        for(String name:List.of("playerHistoryLocks","playerHistoryGenerations","activePlayerRequests","activeStreamOutputs","conversationMemories")) {
            var f=McAI.class.getDeclaredField(name); f.setAccessible(true); f.set(plugin,new java.util.concurrent.ConcurrentHashMap<>());
        }
        var candidate=McAI.class.getDeclaredField("candidateConfiguration"); candidate.setAccessible(true); candidate.set(plugin,new ThreadLocal<>());
        plugin.personalModes=java.util.Map.of();
        return plugin;
    }
    @Test void clearingDisabledPersistenceInvalidatesOnlyTargetPlayer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var plugin=isolatedPlugin(); plugin.historyPersistence=new HistoryPersistence(directory.toFile());
        var id=java.util.UUID.randomUUID(); var other=java.util.UUID.randomUUID();
        var history=new ConversationHistory(ConversationHistory.Mode.SLIDING,3); history.addRound(round("old"));
        plugin.conversationMemories.put(id,history); plugin.conversationMemories.put(other,new ConversationHistory(ConversationHistory.Mode.SLIDING,3));
        Object old=new Object(), current=new Object(); plugin.activePlayerRequests.put(id,old); plugin.activePlayerRequests.put(other,current);
        plugin.historyPersistence.save(id,history); long queued=plugin.historyPersistence.reserveSave(id);
        plugin.historyPersistenceEnabled=false; plugin.clearPlayerHistory(id);
        assertEquals(1,plugin.playerHistoryGeneration(id)); assertNull(plugin.activePlayerRequests.get(id)); assertFalse(plugin.conversationMemories.containsKey(id));
        assertSame(current,plugin.activePlayerRequests.get(other)); assertTrue(plugin.conversationMemories.containsKey(other));
        plugin.historyPersistence.saveMessages(id,history.persistedMessages(),queued);
        assertFalse(java.nio.file.Files.exists(directory.resolve("history/"+id+".yml")));
        plugin.activePlayerRequests.put(id,current); plugin.activePlayerRequests.remove(id,old); assertSame(current,plugin.activePlayerRequests.get(id));
    }
    @Test void invalidConfigurationCandidatePreservesAllAcceptedModesAndCache() throws Exception {
        var plugin=isolatedPlugin();
        var good=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/config.yml"),java.nio.charset.StandardCharsets.UTF_8));
        plugin.applyConfigurationCandidate(good);
        var modes=plugin.personalModes; int timeout=plugin.apiTimeoutSeconds;
        var bad=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.StringReader(good.saveToString()));
        bad.set("ai.personal.modes.advanced-thinking.provider","unknown"); bad.set("ai.adapters.request.timeout-seconds",999);
        assertThrows(IllegalArgumentException.class,()->plugin.applyConfigurationCandidate(bad));
        assertSame(modes,plugin.personalModes); assertSame(good,plugin.getConfig()); assertEquals(timeout,plugin.apiTimeoutSeconds);
    }
}

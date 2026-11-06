package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {
    private static List<ApiAdapter.Message> round(String n) { return List.of(new ApiAdapter.Message("user", "u" + n), new ApiAdapter.Message("assistant", "a" + n)); }
    @Test void slidingRemovesWholeOldRounds() { var h = new ConversationHistory(ConversationHistory.Mode.SLIDING, 2); h.addRound(round("1")); h.addRound(round("2")); h.addRound(round("3")); assertEquals(List.of("u2","a2","u3","a3"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList()); }
    @Test void slidingNeverPinsAnOldConversationRound() {
        var h = new ConversationHistory(ConversationHistory.Mode.SLIDING, 2);
        for (int i = 1; i <= 5; i++) h.addRound(round(String.valueOf(i)));
        assertEquals(List.of("u4", "a4", "u5", "a5"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList());
    }
    @Test void accumulateStopsAfterCapButKeepsExisting() { var h = new ConversationHistory(ConversationHistory.Mode.ACCUMULATE, 1); h.addRound(round("1")); h.addRound(round("2")); assertTrue(h.capped()); assertEquals(List.of("u1","a1"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList()); }
    @Test void incompleteRoundRejected() { var h = new ConversationHistory(ConversationHistory.Mode.SLIDING, 2); assertThrows(IllegalArgumentException.class, () -> h.addRound(List.of(new ApiAdapter.Message("user", "u")))); }
    @Test void anthropicThinkingBlockDoesNotEndRoundEarly() {
        var h = ConversationHistory.fromMessages(ConversationHistory.Mode.SLIDING, 2, List.of(
                new ConversationHistory.Message("user", "u", 1),
                new ConversationHistory.Message("assistant", "thought", 2, "thinking", "sig"),
                new ConversationHistory.Message("assistant", "answer", 3, "text", null)));
        assertEquals(1, h.roundCount());
        assertEquals(List.of("u", "thought", "answer"), h.promptMessages().stream().map(ApiAdapter.Message::content).toList());
    }
}

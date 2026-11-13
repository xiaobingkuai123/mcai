package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleRoundLimitTest {
    private static List<ApiAdapter.Message> round() {
        return List.of(new ApiAdapter.Message("user", "q"), new ApiAdapter.Message("assistant", "a"));
    }

    @Test void accumulateRejectsAtLimitAndClearAllowsAnotherRound() {
        var limit = new ConsoleRoundLimit();
        var history = new ConversationHistory(ConversationHistory.Mode.ACCUMULATE, 2);
        var first = limit.reserve(0, 2, ConversationHistory.Mode.ACCUMULATE);
        assertNotNull(first);
        assertTrue(limit.complete(first, history, round()));
        limit.finish(first, true);
        var second = limit.reserve(history.roundCount(), 2, ConversationHistory.Mode.ACCUMULATE);
        assertNotNull(second);
        assertTrue(limit.complete(second, history, round()));
        limit.finish(second, true);
        assertNull(limit.reserve(history.roundCount(), 2, ConversationHistory.Mode.ACCUMULATE));
        history.reset();
        limit.clear();
        assertNotNull(limit.reserve(history.roundCount(), 2, ConversationHistory.Mode.ACCUMULATE));
    }

    @Test void concurrentRequestsReserveSlotsAndFailureReleasesReservation() {
        var limit = new ConsoleRoundLimit();
        var first = limit.reserve(0, 1, ConversationHistory.Mode.ACCUMULATE);
        assertNotNull(first);
        assertNull(limit.reserve(0, 1, ConversationHistory.Mode.ACCUMULATE));
        limit.finish(first, false);
        assertNotNull(limit.reserve(0, 1, ConversationHistory.Mode.ACCUMULATE));
    }

    @Test void clearingInvalidatesInflightResponseBeforeItCanAppend() {
        var limit = new ConsoleRoundLimit();
        var history = new ConversationHistory(ConversationHistory.Mode.ACCUMULATE, 2);
        var request = limit.reserve(0, 2, ConversationHistory.Mode.ACCUMULATE);
        assertNotNull(request);
        history.reset();
        limit.clear();
        assertFalse(limit.complete(request, history, round()));
        limit.finish(request, true);
        assertEquals(0, history.roundCount());
        assertNotNull(limit.reserve(0, 2, ConversationHistory.Mode.ACCUMULATE));
    }

    @Test void slidingDoesNotApplyAccumulateRequestBlocking() {
        var limit = new ConsoleRoundLimit();
        assertNotNull(limit.reserve(20, 2, ConversationHistory.Mode.SLIDING));
        assertNotNull(limit.reserve(20, 2, ConversationHistory.Mode.SLIDING));
    }

    @Test void zeroRoundLimitBlocksAccumulateRequests() {
        assertNull(new ConsoleRoundLimit().reserve(0, 0, ConversationHistory.Mode.ACCUMULATE));
    }
}

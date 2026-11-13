package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import java.util.List;

/** Reserves console conversation rounds while accumulate-mode requests are in flight. */
final class ConsoleRoundLimit {
    record Reservation(long generation, boolean counted) {}

    private int completedRounds;
    private int inFlight;
    private long generation;

    synchronized Reservation reserve(int historyRounds, int maximumRounds,
                                     ConversationHistory.Mode mode) {
        if (mode != ConversationHistory.Mode.ACCUMULATE) return new Reservation(generation, false);
        completedRounds = Math.max(completedRounds, historyRounds);
        if ((long) completedRounds + inFlight >= Math.max(0, maximumRounds)) return null;
        inFlight++;
        return new Reservation(generation, true);
    }

    synchronized boolean complete(Reservation reservation, ConversationHistory history,
                                  List<ApiAdapter.Message> round) {
        if (reservation.generation() != generation) return false;
        history.addRound(round);
        return true;
    }

    synchronized void finish(Reservation reservation, boolean completed) {
        if (!reservation.counted() || reservation.generation() != generation) return;
        inFlight--;
        if (completed) completedRounds++;
    }

    synchronized void clear() {
        generation++;
        completedRounds = 0;
        inFlight = 0;
    }
}

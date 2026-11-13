package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import java.util.*;

/** Thread-safe memory of complete turns. Provider block metadata is retained for replay. */
public final class ConversationHistory {
    public enum Mode { SLIDING, ACCUMULATE }
    public record Message(String role, String content, long time, String blockType, String signature) {
        public Message(String role, String content, long time) { this(role, content, time, "text", null); }
        public Message(ApiAdapter.Message message, long time) { this(message.role(), message.content(), time, message.blockType(), message.signature()); }
    }
    public record Round(List<Message> messages) {
        public Round { messages = List.copyOf(messages); }
        public boolean complete() {
            boolean user = false, assistant = false;
            for (Message m : messages) { user |= "user".equals(m.role()); assistant |= "assistant".equals(m.role()); }
            return user && assistant;
        }
    }

    private final List<Round> rounds = new ArrayList<>();
    private final Mode mode;
    private final int maxRounds;
    private boolean capped;

    public ConversationHistory(Mode mode, int maxRounds) {
        this.mode = Objects.requireNonNull(mode);
        this.maxRounds = Math.max(0, maxRounds);
    }
    public synchronized List<Round> rounds() { return List.copyOf(rounds); }
    public synchronized int roundCount() { return rounds.size(); }
    public synchronized boolean capped() { return capped; }
    public synchronized boolean canAddRound() {
        return mode != Mode.ACCUMULATE || (!capped && rounds.size() < maxRounds);
    }
    public synchronized int maxRounds() { return maxRounds; }
    public synchronized void markCapped() { capped = true; }
    public synchronized void reset() { rounds.clear(); capped = false; }
    public synchronized void addRound(List<ApiAdapter.Message> messages) {
        List<Message> copy = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ApiAdapter.Message m : messages) copy.add(new Message(m, now));
        if (copy.isEmpty()) return;
        Round round = new Round(copy);
        if (!round.complete()) throw new IllegalArgumentException("A round must contain user and assistant messages");
        if (maxRounds == 0) { capped = true; return; }
        if (mode == Mode.ACCUMULATE && (capped || rounds.size() >= maxRounds)) { capped = true; return; }
        rounds.add(round);
        if (mode == Mode.SLIDING) while (rounds.size() > maxRounds) rounds.remove(0);
        if (mode == Mode.ACCUMULATE && rounds.size() >= maxRounds) capped = true;
    }
    public synchronized List<ApiAdapter.Message> promptMessages() {
        List<ApiAdapter.Message> result = new ArrayList<>();
        for (Round r : rounds) for (Message m : r.messages()) result.add(new ApiAdapter.Message(m.role(), m.content(), m.blockType(), m.signature()));
        return List.copyOf(result);
    }
    public synchronized List<Message> persistedMessages() {
        List<Message> result = new ArrayList<>();
        for (Round r : rounds) result.addAll(r.messages());
        return List.copyOf(result);
    }
    public static ConversationHistory fromMessages(Mode mode, int maxRounds, List<Message> messages) {
        ConversationHistory h = new ConversationHistory(mode, maxRounds);
        List<ApiAdapter.Message> current = new ArrayList<>();
        boolean user = false;
        for (Message m : messages) {
            if ("user".equals(m.role())) { if (user && !current.isEmpty()) { if (current.stream().anyMatch(x -> "assistant".equals(x.role()))) h.addRound(current); current = new ArrayList<>(); } user = true; }
            current.add(new ApiAdapter.Message(m.role(), m.content(), m.blockType(), m.signature()));
        }
        if (user && !current.isEmpty() && current.stream().anyMatch(m -> "assistant".equals(m.role()))) h.addRound(current);
        return h;
    }
}

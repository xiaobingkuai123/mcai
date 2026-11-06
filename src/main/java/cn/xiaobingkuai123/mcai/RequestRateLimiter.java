package cn.xiaobingkuai123.mcai;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player atomic updates; cleanup uses the same map operation boundary. */
final class RequestRateLimiter {
    private record Window(long lastRequest, int count) {}
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    boolean acquire(UUID id, int limit, long now) {
        boolean[] accepted = {false};
        windows.compute(id, (key, previous) -> {
            int count = previous == null || now - previous.lastRequest() > 60_000 ? 0 : previous.count();
            if (count >= limit) return previous;
            accepted[0] = true;
            return new Window(now, count + 1);
        });
        return accepted[0];
    }

    void remove(UUID id) { windows.remove(id); }
    void clear() { windows.clear(); }
}

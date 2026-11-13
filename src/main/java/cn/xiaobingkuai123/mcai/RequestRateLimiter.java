package cn.xiaobingkuai123.mcai;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Atomic per-player sliding window; time is measured only with a monotonic clock. */
final class RequestRateLimiter implements AutoCloseable {
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final ConcurrentHashMap<UUID, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private volatile boolean closed;
    private final ScheduledExecutorService cleaner;
    RequestRateLimiter() { this(System::nanoTime); }
    RequestRateLimiter(LongSupplier clock) {
        this.clock = clock;
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "McAI-rate-cleanup"); t.setDaemon(true); return t; });
        cleaner.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }
    boolean acquire(UUID id, int limit) { return acquireUsing(id, limit, clock); }
    /** Compatibility overload: explicit elapsed milliseconds. Production uses nanoTime. */
    boolean acquire(UUID id, int limit, long now) { return acquireNanos(id, limit, TimeUnit.MILLISECONDS.toNanos(now)); }
    private boolean acquireNanos(UUID id, int limit, long now) { return acquireUsing(id, limit, () -> now); }
    private boolean acquireUsing(UUID id, int limit, LongSupplier time) {
        if (closed) return false;
        boolean[] accepted = {false};
        windows.compute(id, (key, queue) -> {
            long now = time.getAsLong();
            if (queue == null) queue = new ArrayDeque<>();
            prune(queue, now);
            if (queue.size() < limit) { queue.addLast(now); accepted[0] = true; }
            return queue.isEmpty() ? null : queue;
        });
        if (closed) {
            windows.remove(id);
            return false;
        }
        return accepted[0];
    }
    private static void prune(ArrayDeque<Long> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst() >= WINDOW_NANOS) queue.removeFirst();
    }
    void cleanup() {
        long now = clock.getAsLong();
        windows.forEach((id, ignored) -> windows.computeIfPresent(id, (key, queue) -> {
            prune(queue, now); return queue.isEmpty() ? null : queue;
        }));
    }
    int trackedPlayers() { return windows.size(); }
    void remove(UUID id) { windows.remove(id); }
    void clear() { windows.clear(); }
    @Override public void close() { closed = true; cleaner.shutdownNow(); clear(); }
}

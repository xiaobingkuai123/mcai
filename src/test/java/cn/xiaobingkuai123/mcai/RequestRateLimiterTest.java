package cn.xiaobingkuai123.mcai;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimiterTest {
    @Test void concurrentRequestsRespectPerPlayerLimitAndCleanupResetsIt() throws Exception {
        var limiter = new RequestRateLimiter();
        UUID id = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 100).<Callable<Boolean>>mapToObj(i -> () -> limiter.acquire(id, 10, 1000)).toList();
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertEquals(10, accepted);
        }
        assertTrue(limiter.acquire(UUID.randomUUID(), 10, 1000));
        assertTrue(limiter.acquire(id, 10, 61_001));
        limiter.remove(id);
        assertTrue(limiter.acquire(id, 1, 61_001));
        assertFalse(limiter.acquire(id, 1, 61_001));
    }
}

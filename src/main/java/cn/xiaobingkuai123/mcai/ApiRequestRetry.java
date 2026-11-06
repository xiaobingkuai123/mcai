package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import java.io.IOException;
import java.net.http.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Retries only transport failures and retryable HTTP responses, before any visible stream output. */
final class ApiRequestRetry {
    @FunctionalInterface interface Operation<T> { T run() throws Exception; }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    private final BooleanSupplier active;
    private final Pause pause;

    ApiRequestRetry(BooleanSupplier active) { this(active, Thread::sleep); }
    ApiRequestRetry(BooleanSupplier active, Pause pause) { this.active = active; this.pause = pause; }

    HttpResponse<String> send(HttpClient client, HttpRequest request, int retries) throws Exception {
        return execute(() -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (retryable(response.statusCode())) throw new AiStreamClient.ApiHttpException(response.statusCode());
            return response;
        }, retries, () -> false);
    }

    ApiAdapter.AdapterResponse stream(HttpClient client, ApiAdapter adapter, HttpRequest request,
            Consumer<AiStreamClient.Delta> listener, int retries) throws Exception {
        AtomicBoolean emitted = new AtomicBoolean();
        return execute(() -> AiStreamClient.send(client, adapter, request, delta -> {
            checkActive();
            emitted.set(true);
            listener.accept(delta);
        }), retries, emitted::get);
    }

    <T> T execute(Operation<T> operation, int retries, BooleanSupplier emitted) throws Exception {
        int attempt = 0;
        while (true) {
            checkActive();
            try {
                T result = operation.run();
                checkActive();
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (IOException e) {
                if (e instanceof AiStreamClient.ProtocolException
                        || (e instanceof AiStreamClient.ApiHttpException http && !retryable(http.statusCode()))
                        || emitted.getAsBoolean() || attempt >= Math.max(0, retries)) throw e;
            }
            checkActive();
            long remaining = Math.min(1000L << Math.min(attempt++, 4), 10_000L);
            // Observe logout/shutdown promptly, including during exponential backoff.
            while (remaining > 0) {
                long slice = Math.min(100, remaining);
                try { pause.sleep(slice); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                remaining -= slice;
                checkActive();
            }
        }
    }

    private void checkActive() {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) {
            throw new CancellationException("AI request cancelled");
        }
    }

    private static boolean retryable(int status) { return status == 429 || status >= 500 && status <= 599; }
}

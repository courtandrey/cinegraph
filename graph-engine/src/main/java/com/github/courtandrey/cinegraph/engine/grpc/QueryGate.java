package com.github.courtandrey.cinegraph.engine.grpc;

import io.grpc.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class QueryGate {

    private final Semaphore permits;
    private final long queueTimeoutMs;
    private final int maxConcurrent;

    public QueryGate(@Value("${engine.max-concurrent-queries:0}") int maxConcurrentQueries,
                     @Value("${engine.queue-timeout-ms:2000}") long queueTimeoutMs) {
        this.maxConcurrent = maxConcurrentQueries > 0
                ? maxConcurrentQueries
                : Math.max(2, Runtime.getRuntime().availableProcessors());
        this.permits = new Semaphore(maxConcurrent, true);
        this.queueTimeoutMs = queueTimeoutMs;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public <T> T execute(Supplier<T> query) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw Status.RESOURCE_EXHAUSTED
                    .withDescription("engine at capacity: query queue timed out after " + queueTimeoutMs + "ms")
                    .asRuntimeException();
        }
        try {
            return query.get();
        } finally {
            permits.release();
        }
    }
}

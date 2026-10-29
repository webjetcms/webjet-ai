package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AiCancellationTokenTest {

    @Test
    void cancellationIsIdempotentAndReleasesCompletedRegistrations() {
        AiCancellationToken token = new AiCancellationToken();
        AtomicInteger calls = new AtomicInteger();
        AiCancellationToken.Registration completed = token.onCancel(() -> {
            throw new AssertionError("Completed resources must not be aborted");
        });
        completed.close();
        try (AiCancellationToken.Registration active = token.onCancel(calls::incrementAndGet)) {
            assertTrue(token.cancel());
            assertFalse(token.cancel());
            assertEquals(1, calls.get());
            try (AiCancellationToken.Registration late = token.onCancel(calls::incrementAndGet)) {
                assertEquals(2, calls.get());
            }
        }
    }

    @Test
    void racingCancellationAndRegistrationNeverLoseOrRepeatTheAbort() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                AiCancellationToken token = new AiCancellationToken();
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                Future<AiCancellationToken.Registration> registration = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return token.onCancel(calls::incrementAndGet);
                });
                Future<Boolean> cancellation = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return token.cancel();
                });
                start.countDown();
                assertTrue(cancellation.get(5, TimeUnit.SECONDS));
                try (AiCancellationToken.Registration completed = registration.get(5, TimeUnit.SECONDS)) {
                    assertEquals(1, calls.get(), "Every race must invoke its abort exactly once");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }
}

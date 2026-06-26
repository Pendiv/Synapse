package dev.div.synapse.core;

import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link MainThread#runOn(Executor, ThrowingSupplier, long)} with stand-in executors
 * (no Minecraft runtime) — covering the timeout and the cancel-on-timeout / isDone guard that
 * stops a side effect firing after the caller already gave up.
 */
class MainThreadTest {

    /** Runs each task on a fresh daemon thread, like the game thread would. */
    private static final Executor ASYNC = r -> {
        Thread t = new Thread(r, "test-game-thread");
        t.setDaemon(true);
        t.start();
    };

    @Test
    void returnsValueWhenTaskCompletes() throws Exception {
        assertEquals("ok", MainThread.runOn(ASYNC, () -> "ok", 2000));
    }

    @Test
    void propagatesSynapseExceptionUnchanged() {
        SynapseException ex = assertThrows(SynapseException.class, () ->
                MainThread.runOn(ASYNC, () -> {
                    throw new SynapseException(SynapseError.NOT_IN_WORLD, "no world");
                }, 2000));
        assertEquals(SynapseError.NOT_IN_WORLD, ex.error);
    }

    @Test
    void wrapsOtherThrowableAsInternal() {
        SynapseException ex = assertThrows(SynapseException.class, () ->
                MainThread.runOn(ASYNC, () -> {
                    throw new IllegalStateException("boom");
                }, 2000));
        assertEquals(SynapseError.INTERNAL, ex.error);
    }

    @Test
    void timesOutThenIsDoneGuardSkipsTheLateSideEffect() throws Exception {
        AtomicBoolean sideEffectRan = new AtomicBoolean(false);
        // Executor that captures the task but does NOT run it, so we control timing.
        Runnable[] captured = new Runnable[1];
        Executor deferred = r -> captured[0] = r;

        // runOn blocks on future.get; run it off-thread so we can act after it times out.
        ExecutorService probe = Executors.newSingleThreadExecutor();
        try {
            Future<SynapseException> thrown = probe.submit(() -> {
                try {
                    MainThread.runOn(deferred, () -> {
                        sideEffectRan.set(true);
                        return "late";
                    }, 50);
                    return null;
                } catch (SynapseException e) {
                    return e;
                }
            });

            SynapseException ex = thrown.get(2, TimeUnit.SECONDS);
            assertNotNull(ex, "should have thrown");
            assertEquals(SynapseError.TIMEOUT, ex.error);

            // Deterministic ordering: the task is dispatched only AFTER the timeout cancelled the
            // future, so the isDone() guard must short-circuit it. (This proves the guard's mechanism;
            // it does not exercise the racy interleaving where the task starts just as the timeout fires.)
            assertNotNull(captured[0]);
            captured[0].run();
            assertFalse(sideEffectRan.get(), "isDone guard must skip the task once the future is cancelled");
        } finally {
            probe.shutdownNow();
        }
    }
}

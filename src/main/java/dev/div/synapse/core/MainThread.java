package dev.div.synapse.core;

import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges HTTP handler threads onto a game thread (spec §3.1).
 *
 * <p>Game state may only be touched on the owning thread: client state on the
 * Minecraft main thread, world-mutating commands on the integrated server
 * thread. Handlers schedule work here; the handler thread blocks on the result
 * with a timeout. <b>Never call this from the target thread itself</b> — waiting
 * on the future there would deadlock.
 */
public final class MainThread {

    private MainThread() {
    }

    /** Runs {@code task} on the Minecraft client main thread. */
    public static <T> T run(ThrowingSupplier<T> task, long timeoutMs) throws SynapseException {
        return runOn(Minecraft.getInstance(), task, timeoutMs);
    }

    /**
     * Runs {@code task} on the given game executor (the client {@link Minecraft}
     * or the integrated {@code MinecraftServer}) and returns its result,
     * blocking the calling (handler) thread up to {@code timeoutMs}.
     *
     * @throws SynapseException {@code TIMEOUT} if the deadline passes, the
     *         original {@link SynapseException} if the task threw one, or
     *         {@code INTERNAL} for any other failure.
     */
    public static <T> T runOn(Executor executor, ThrowingSupplier<T> task, long timeoutMs) throws SynapseException {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            // If the caller already gave up (timeout/cancel), skip the task entirely.
            // This prevents side effects (e.g. a command) from firing on the game
            // thread after the client was already told the request timed out.
            if (future.isDone()) {
                return;
            }
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Mark the future done so the still-queued task short-circuits when it runs.
            future.cancel(false);
            throw new SynapseException(SynapseError.TIMEOUT,
                    "Main-thread task did not complete within " + timeoutMs + " ms. "
                            + "The game may be paused, loading, or hung.");
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new SynapseException(SynapseError.INTERNAL,
                    "Interrupted while waiting for the main thread.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SynapseException se) {
                throw se;
            }
            throw new SynapseException(SynapseError.INTERNAL,
                    "Main-thread task failed: " + cause.getClass().getSimpleName()
                            + ": " + cause.getMessage(), cause);
        }
    }
}

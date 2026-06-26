package dev.div.synapse.config;

import java.util.Locale;

/**
 * How much an AI client is allowed to do, in increasing order. The configured
 * {@code accessLevel} gates every mutating operation, so even a prompt-injected
 * agent cannot exceed the level the human chose.
 *
 * <ul>
 *   <li>{@link #OBSERVE} — read-only (state, gui/chat reads, screenshot, wait).</li>
 *   <li>{@link #PLAY} — observe + anything a human player could do (move, click, plain chat).</li>
 *   <li>{@link #DEVELOPER} — play + arbitrary commands ({@code /cmd}, {@code /}-chat, creative).
 *       This is the original full-power behaviour; it is opt-in and at your own risk.</li>
 * </ul>
 */
public enum AccessLevel {
    OBSERVE(0),
    PLAY(1),
    DEVELOPER(2);

    private final int rank;

    AccessLevel(int rank) {
        this.rank = rank;
    }

    /** True if this configured level satisfies the minimum {@code required} by an operation. */
    public boolean permits(AccessLevel required) {
        return this.rank >= required.rank;
    }

    /** This level, lowered to {@code ceiling} if it exceeds it (granted = min(token, ceiling)). */
    public AccessLevel cappedAt(AccessLevel ceiling) {
        return this.rank <= ceiling.rank ? this : ceiling;
    }

    /** Lowercase id used in config, the manifest, and messages. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}

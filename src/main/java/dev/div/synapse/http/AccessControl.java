package dev.div.synapse.http;

import com.google.gson.JsonObject;
import dev.div.synapse.config.AccessLevel;
import dev.div.synapse.config.SynapseConfig;

import java.util.Locale;

/**
 * The single gate for "is the AI allowed to do this?". Handlers call {@link #require}
 * with the minimum {@link AccessLevel} an operation needs; below that, a clear
 * {@code ACCESS_DENIED} is returned. The level→operation policy lives here so the
 * standalone endpoints and {@code /batch} agree, and the pure pieces are unit-tested.
 */
public final class AccessControl {

    /** The level granted to the in-flight request (from its token), set per request by dispatch. */
    private static final ThreadLocal<AccessLevel> CURRENT = new ThreadLocal<>();

    private AccessControl() {
    }

    /** Records the level granted to the current request (the handler thread reads it via {@link #level}). */
    public static void setCurrent(AccessLevel level) {
        CURRENT.set(level);
    }

    /** Clears the per-request level (call in dispatch's finally so it can't leak to a pooled thread). */
    public static void clearCurrent() {
        CURRENT.remove();
    }

    /** The level in effect: the current request's granted level, else the configured ceiling. */
    public static AccessLevel level() {
        AccessLevel current = CURRENT.get();
        if (current != null) {
            return current;
        }
        try {
            return SynapseConfig.ACCESS_LEVEL.get();
        } catch (Throwable t) {
            return AccessLevel.PLAY;
        }
    }

    public static boolean allows(AccessLevel required) {
        return level().permits(required);
    }

    /** Throws {@code ACCESS_DENIED} unless the configured level permits {@code required}. */
    public static void require(AccessLevel required) throws SynapseException {
        AccessLevel current = level();
        if (!current.permits(required)) {
            throw new SynapseException(SynapseError.ACCESS_DENIED,
                    "This operation needs accessLevel '" + required.id() + "', but Synapse is set to '"
                            + current.id() + "'.")
                    .detail("required", required.id())
                    .detail("current", current.id())
                    .detail("hint", "Raise accessLevel in synapse-client.toml. 'developer' is full power "
                            + "(arbitrary commands) and is at your own risk.");
        }
    }

    // === pure policy: which level an operation needs (unit-tested) ===

    /** A GUI POST needs DEVELOPER to open the creative inventory, else PLAY. */
    public static AccessLevel levelForGui(JsonObject body) {
        String action = HttpUtil.str(body, "action", "").toLowerCase(Locale.ROOT);
        String target = HttpUtil.str(body, "target", "").toLowerCase(Locale.ROOT);
        return action.equals("open") && target.equals("creative") ? AccessLevel.DEVELOPER : AccessLevel.PLAY;
    }

    /** Sending chat needs DEVELOPER if the text is a command (starts with '/'), else PLAY. */
    public static AccessLevel levelForChat(String text) {
        return isCommand(text) ? AccessLevel.DEVELOPER : AccessLevel.PLAY;
    }

    /** True if chat text would be run as a command rather than sent as a message. */
    public static boolean isCommand(String text) {
        return text != null && text.strip().startsWith("/");
    }
}

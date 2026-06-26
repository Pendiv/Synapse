package dev.div.synapse.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.div.synapse.Synapse;
import dev.div.synapse.http.RequestGuards;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves auth tokens. Secure by default, and <b>the token carries the privilege</b>:
 * each {@link AccessLevel} gets its own token, so you hand an agent the token for the
 * level you want it to have (observe / play / developer). The granted level is
 * {@code min(token's level, configured accessLevel ceiling)}, so a developer token is
 * still capped until the human raises the ceiling — developer stays opt-in.
 *
 * <p>Per-level tokens live in {@code ~/.synapse/tokens.json} (0600). For zero-setup,
 * {@code ~/.synapse/token} mirrors the ceiling-level token (the bundled MCP reads it).
 * An explicit config {@code authToken} switches to a single token granted up to the ceiling.
 */
public final class AuthToken {

    public enum Source { CONFIG, FILE, GENERATED, NONE }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile boolean resolved = false;
    private static volatile Source source = Source.NONE;
    private static volatile boolean singleMode = false;
    private static volatile String singleToken = "";
    private static volatile Map<AccessLevel, String> tokens; // native level -> token (multi mode)

    private AuthToken() {
    }

    public static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        String configured = SynapseConfig.AUTH_TOKEN.get();
        if (configured != null && !configured.isBlank()) {
            singleMode = true;
            singleToken = configured.strip();
            source = Source.CONFIG;
            return;
        }

        Path file = tokensFile();
        Map<AccessLevel, String> loaded = load(file);
        if (loaded != null) {
            tokens = loaded;
            source = Source.FILE;
        } else {
            Map<AccessLevel, String> generated = new EnumMap<>(AccessLevel.class);
            for (AccessLevel lvl : AccessLevel.values()) {
                generated.put(lvl, generate());
            }
            tokens = generated;
            source = Source.GENERATED;
            persist(file, generated);
        }
        writeDefaultTokenFile();
    }

    /** The native {@link AccessLevel} of the presented token, or {@code null} if unknown/missing. */
    public static AccessLevel levelForToken(String presented) {
        if (presented == null || presented.isEmpty()) {
            return null;
        }
        if (singleMode) {
            // A single explicit token authenticates and is granted up to the ceiling.
            return RequestGuards.constantTimeEquals(presented, singleToken) ? AccessLevel.DEVELOPER : null;
        }
        Map<AccessLevel, String> map = tokens;
        if (map == null) {
            return null;
        }
        AccessLevel match = null;
        // Compare against every level (no early return) so timing can't reveal which matched.
        // If a token were ever duplicated across levels, keep the LOWEST — fail closed.
        for (Map.Entry<AccessLevel, String> e : map.entrySet()) {
            if (RequestGuards.constantTimeEquals(presented, e.getValue())
                    && (match == null || e.getKey().ordinal() < match.ordinal())) {
                match = e.getKey();
            }
        }
        return match;
    }

    /** The configured ceiling — the most any token can grant. */
    public static AccessLevel ceiling() {
        try {
            return SynapseConfig.ACCESS_LEVEL.get();
        } catch (Throwable t) {
            return AccessLevel.PLAY;
        }
    }

    /** The token for the ceiling level — what {@code ~/.synapse/token} holds and the MCP uses. */
    public static String defaultToken() {
        if (singleMode) {
            return singleToken;
        }
        if (tokens == null) {
            return "";
        }
        String t = tokens.get(ceiling());
        return t == null ? "" : t;
    }

    /** Back-compat alias: the default (ceiling) token. */
    public static String effectiveToken() {
        return defaultToken();
    }

    public static boolean enabled() {
        return singleMode ? !singleToken.isEmpty() : (tokens != null && !tokens.isEmpty());
    }

    public static Source source() {
        return source;
    }

    /** {@code ~/.synapse/token} — the ceiling-level token (read by the MCP and curl examples). */
    public static Path tokenFile() {
        return dir().resolve("token");
    }

    /** {@code ~/.synapse/tokens.json} — the per-level tokens (only in multi-token mode). */
    public static Path tokensFile() {
        return dir().resolve("tokens.json");
    }

    private static Path dir() {
        return Paths.get(System.getProperty("user.home", "."), ".synapse");
    }

    // === persistence ===

    private static Map<AccessLevel, String> load(Path file) {
        try {
            if (!Files.isReadable(file)) {
                return null;
            }
            JsonElement el = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            Map<AccessLevel, String> map = new EnumMap<>(AccessLevel.class);
            for (AccessLevel lvl : AccessLevel.values()) {
                JsonElement t = o.get(lvl.id());
                if (t == null || !t.isJsonPrimitive() || t.getAsString().isBlank()) {
                    return null; // incomplete -> regenerate
                }
                map.put(lvl, t.getAsString().strip());
            }
            return map;
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not read {}: {}", file, t.toString());
            return null;
        }
    }

    private static void persist(Path file, Map<AccessLevel, String> map) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            for (Map.Entry<AccessLevel, String> e : map.entrySet()) {
                o.addProperty(e.getKey().id(), e.getValue());
            }
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(file);
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not persist token file {} (using session-only tokens): {}",
                    file, t.toString());
        }
    }

    private static void writeDefaultTokenFile() {
        try {
            String t = defaultToken();
            if (t.isEmpty()) {
                return;
            }
            Files.createDirectories(dir());
            Files.writeString(tokenFile(), t + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(tokenFile());
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not write {}: {}", tokenFile(), t.toString());
        }
    }

    private static String generate() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Best-effort 0600 on POSIX; on Windows the per-user home dir ACL already restricts it. */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Throwable ignored) {
            // Non-POSIX filesystem (Windows) — rely on the user-profile ACL.
        }
    }
}

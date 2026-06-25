package dev.div.synapse.config;

import dev.div.synapse.Synapse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the <b>effective</b> auth token. Secure by default.
 *
 * <p>If the user leaves {@code authToken} blank in the config, Synapse
 * auto-generates a strong random token on first run and persists it to
 * {@code ~/.synapse/token} (machine-local, per-user). The legitimate local
 * agent reads it from there — the bundled MCP server auto-discovers that file,
 * and {@code /synapse} / {@code AGENT.md} print it — but a drive-by web page or
 * another process that cannot read the file is rejected by {@code requireAuth}.
 *
 * <p>An explicit non-blank config {@code authToken} always wins (advanced /
 * remote setups manage their own secret and pass it to the agent themselves).
 *
 * <p>The same file path is used by the bundled MCP server so a single machine
 * shares one token across instances with zero manual steps.
 */
public final class AuthToken {

    /** Where the token came from, for accurate startup logging. */
    public enum Source { CONFIG, FILE, GENERATED, NONE }

    private static volatile String effective;
    private static volatile Source source = Source.NONE;

    private AuthToken() {
    }

    /** Resolves the effective token once (idempotent). Call before the server starts. */
    public static synchronized void resolve() {
        if (effective != null) {
            return;
        }
        String configured = SynapseConfig.AUTH_TOKEN.get();
        if (configured != null && !configured.isBlank()) {
            effective = configured.strip();
            source = Source.CONFIG;
            return;
        }

        Path file = tokenFile();
        try {
            if (Files.isReadable(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!existing.isEmpty()) {
                    effective = existing;
                    source = Source.FILE;
                    return;
                }
            }
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not read token file {}: {}", file, t.toString());
        }

        // Generate a fresh token and try to persist it for next launch / the MCP server.
        String token = generate();
        effective = token;
        source = Source.GENERATED;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, token + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(file);
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not persist token file {} (using a session-only token): {}",
                    file, t.toString());
        }
    }

    /** The effective token, or {@code ""} if none (only before {@link #resolve()} or on total failure). */
    public static String effectiveToken() {
        return effective == null ? "" : effective;
    }

    /** Whether auth is in force (true by default thanks to auto-generation). */
    public static boolean enabled() {
        return !effectiveToken().isEmpty();
    }

    public static Source source() {
        return source;
    }

    /** {@code ~/.synapse/token} — the per-user, machine-local token file (also read by the MCP server). */
    public static Path tokenFile() {
        return Paths.get(System.getProperty("user.home", "."), ".synapse", "token");
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

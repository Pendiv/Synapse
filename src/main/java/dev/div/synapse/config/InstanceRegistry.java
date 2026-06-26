package dev.div.synapse.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.div.synapse.Synapse;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Advertises this running client's bridge in {@code ~/.synapse/instances.json} so a
 * single AI (the bundled MCP server) can discover and drive several Minecraft
 * instances at once. Each entry carries a per-launch {@code instanceId} and {@code pid}
 * so consumers can tell a live instance from a crash-stale one and detect port reuse.
 *
 * <p>Robustness: the read-modify-write is serialised across processes by a lock file,
 * written to a temp file and atomically renamed (so a crash never leaves a truncated
 * file), and restricted to {@code 0600} (the token lives here too). On {@link #register}
 * we evict any prior entry with our game directory <em>or</em> our port — a port belongs
 * to exactly one live process, so this clears a stale entry whose port we just reused.
 */
public final class InstanceRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private InstanceRegistry() {
    }

    private static Path dir() {
        return Paths.get(System.getProperty("user.home", "."), ".synapse");
    }

    public static Path file() {
        return dir().resolve("instances.json");
    }

    /** The human-readable instance name (the game directory's folder name). */
    public static String instanceName() {
        Path g = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path fn = g.getFileName();
        return fn == null ? g.toString() : fn.toString();
    }

    private static String gameDir() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString();
    }

    /** Upserts this instance's entry (evicting any stale entry sharing our gamedir or port). */
    public static synchronized void register(String instanceId, int port, String baseUrl,
                                             String token, String mcVersion, long startedAt) {
        try {
            update(arr -> {
                String gd = gameDir();
                removeIf(arr, o -> gd.equals(str(o, "gamedir")) || port == intval(o, "port"));
                JsonObject o = new JsonObject();
                o.addProperty("name", instanceName());
                o.addProperty("instanceId", instanceId);
                o.addProperty("gamedir", gd);
                o.addProperty("pid", ProcessHandle.current().pid());
                o.addProperty("port", port);
                o.addProperty("baseUrl", baseUrl);
                if (token != null && !token.isEmpty()) {
                    o.addProperty("token", token);
                }
                o.addProperty("mcVersion", mcVersion);
                o.addProperty("startedAt", startedAt);
                arr.add(o);
            });
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not register instance in {}: {}", file(), t.toString());
        }
    }

    /** Removes this instance's entry. Best-effort (a hard kill skips this — consumers handle staleness). */
    public static synchronized void unregister() {
        try {
            String gd = gameDir();
            update(arr -> removeIf(arr, o -> gd.equals(str(o, "gamedir"))));
        } catch (Throwable t) {
            Synapse.LOGGER.warn("[Synapse] Could not unregister instance: {}", t.toString());
        }
    }

    private interface ArrayMutator {
        void apply(JsonArray arr);
    }

    private static void update(ArrayMutator mutator) throws IOException {
        Files.createDirectories(dir());
        // Serialise cross-process writers on a dedicated lock file (not the data file,
        // which we replace by atomic rename).
        try (FileChannel lockCh = FileChannel.open(dir().resolve("instances.json.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lockCh.lock()) {

            String content = "";
            if (Files.exists(file())) {
                try {
                    content = Files.readString(file(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    content = ""; // unreadable -> treat as fresh (we overwrite atomically anyway)
                }
            }
            JsonObject root = parse(content);
            JsonArray arr = root.has("instances") && root.get("instances").isJsonArray()
                    ? root.getAsJsonArray("instances") : new JsonArray();
            mutator.apply(arr);
            root.add("instances", arr);

            Path tmp = dir().resolve("instances.json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file());
        }
    }

    private static JsonObject parse(String content) {
        if (content == null || content.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement el = GSON.fromJson(content, JsonElement.class);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    private interface Pred {
        boolean test(JsonObject o);
    }

    private static void removeIf(JsonArray arr, Pred p) {
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonElement e = arr.get(i);
            if (e.isJsonObject() && p.test(e.getAsJsonObject())) {
                arr.remove(i);
            }
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int intval(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Best-effort 0600 on POSIX; on Windows the per-user home dir ACL already restricts it. */
    private static void restrictPermissions(Path f) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(f, perms);
        } catch (Throwable ignored) {
            // Non-POSIX filesystem (Windows) — rely on the user-profile ACL.
        }
    }
}

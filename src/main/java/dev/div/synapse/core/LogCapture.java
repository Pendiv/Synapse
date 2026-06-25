package dev.div.synapse.core;

import com.google.gson.JsonArray;
import dev.div.synapse.config.SynapseConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Captures recent log lines into a bounded ring buffer so every response can
 * carry the {@code logs} field (spec §8). The buffer is trimmed to
 * {@code config.logBufferSize}.
 *
 * <p>By default the appender is attached to the {@code dev.div.synapse} package
 * logger, so {@code logs} carries only Synapse's own lines — not the whole game's
 * root log (which would leak other mods' diagnostics, file paths, and server
 * addresses to any client that can read a response). Set
 * {@code captureAllModLogs=true} to attach to the root logger instead.
 */
public final class LogCapture {

    /** The package logger we scope to by default; the mod's own logger lives under it. */
    private static final String SCOPE = "dev.div.synapse";

    private static final ConcurrentLinkedDeque<String> BUFFER = new ConcurrentLinkedDeque<>();
    private static volatile boolean installed = false;
    private static RingAppender appender;
    private static boolean attachedToRoot = false;

    private LogCapture() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        appender = new RingAppender();
        appender.start();
        attachedToRoot = captureAll();
        target(ctx).addAppender(appender);
        // Re-resolve cached loggers so an inserted package LoggerConfig actually receives events.
        ctx.updateLoggers();
        installed = true;
    }

    public static synchronized void uninstall() {
        if (!installed) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        target(ctx).removeAppender(appender);
        ctx.updateLoggers();
        appender.stop();
        appender = null;
        installed = false;
    }

    private static Logger target(LoggerContext ctx) {
        return attachedToRoot ? ctx.getRootLogger() : ctx.getLogger(SCOPE);
    }

    private static boolean captureAll() {
        try {
            return SynapseConfig.CAPTURE_ALL_MOD_LOGS.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Snapshot of recent log lines (oldest → newest) as a JSON array. */
    public static JsonArray recentAsJson() {
        JsonArray arr = new JsonArray();
        for (String s : BUFFER) {
            arr.add(s);
        }
        return arr;
    }

    private static synchronized void add(String line) {
        BUFFER.addLast(line);
        int max = capacity();
        while (BUFFER.size() > max) {
            BUFFER.pollFirst();
        }
    }

    private static int capacity() {
        try {
            return Math.max(0, SynapseConfig.LOG_BUFFER_SIZE.get());
        } catch (Throwable t) {
            return 30;
        }
    }

    /** Bounded appender. Filter/layout are null — we format minimally ourselves. */
    private static final class RingAppender extends AbstractAppender {
        RingAppender() {
            super("SynapseLogCapture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (capacity() == 0) {
                return;
            }
            String logger = event.getLoggerName();
            String line = "[" + event.getLevel() + "] [" + event.getThreadName() + "/"
                    + (logger == null || logger.isEmpty() ? "?" : shortName(logger)) + "] "
                    + event.getMessage().getFormattedMessage();
            add(line);
        }

        private static String shortName(String logger) {
            int dot = logger.lastIndexOf('.');
            return dot >= 0 && dot < logger.length() - 1 ? logger.substring(dot + 1) : logger;
        }
    }
}

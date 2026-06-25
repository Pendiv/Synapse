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
 * carry the {@code logs} field (spec §8). A Log4j2 appender is attached to the
 * root logger; the buffer is trimmed to {@code config.logBufferSize}.
 */
public final class LogCapture {

    private static final ConcurrentLinkedDeque<String> BUFFER = new ConcurrentLinkedDeque<>();
    private static volatile boolean installed = false;
    private static RingAppender appender;

    private LogCapture() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        appender = new RingAppender();
        appender.start();
        Logger root = ctx.getRootLogger();
        root.addAppender(appender);
        installed = true;
    }

    public static synchronized void uninstall() {
        if (!installed) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getRootLogger().removeAppender(appender);
        appender.stop();
        appender = null;
        installed = false;
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

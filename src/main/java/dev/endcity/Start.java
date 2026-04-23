package dev.endcity;

import dev.endcity.server.MinecraftServer;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Main entry point for EndCity.
 *
 * <p>Current net version is 560. See {@code EndCity_Design.md} §0 for the three distinct version
 * numbers in play.
 */
public final class Start {

    public static void main(String[] args) {
        configureLogging();
        Logger.getLogger(Start.class.getName()).info("Server starting");

        MinecraftServer server = new MinecraftServer();
        server.start();
    }

    private static void configureLogging() {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new ShortFormatter());
        root.addHandler(handler);

        // Redirect the noisy default stdout "Server Started" line into the log channel too.
        try { System.setOut(System.out); } catch (SecurityException ignored) {}
    }

    /** Compact one-line formatter: {@code HH:mm:ss.SSS LEVEL [thread] message}. */
    private static final class ShortFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            long ms = r.getMillis();
            long sec = ms / 1000;
            long h = (sec / 3600) % 24;
            long m = (sec / 60) % 60;
            long s = sec % 60;
            StringBuilder sb = new StringBuilder(128);
            sb.append(String.format("%02d:%02d:%02d.%03d ", h, m, s, ms % 1000));
            sb.append(String.format("%-7s", r.getLevel().getName()));
            sb.append('[').append(Thread.currentThread().getName()).append("] ");
            sb.append(formatMessage(r));
            sb.append(System.lineSeparator());
            if (r.getThrown() != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                r.getThrown().printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}

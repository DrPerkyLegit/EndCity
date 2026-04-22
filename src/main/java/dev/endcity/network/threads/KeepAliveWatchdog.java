package dev.endcity.network.threads;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.NetworkManager;
import dev.endcity.network.connection.ConnectionState;
import dev.endcity.network.connection.PlayerConnection;
import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 1 Hz scheduler that drives keep-alive emission and watchdog timeouts for every
 * {@link PlayerConnection} in the server. Owned by {@link NetworkManager} and started with the
 * rest of the network stack.
 *
 * <p>Thresholds are source-pinned:
 * <ul>
 *   <li>Keep-alive interval: 1 s
 *       ({@code Minecraft.Client/PlayerConnection.cpp:128} — {@code tickCount - lastKeepAliveTick > 20*1}).</li>
 *   <li>Timeout disconnect: 60 s
 *       ({@code Minecraft.World/Connection.h:34} — {@code MAX_TICKS_WITHOUT_INPUT = 20 * 60}).</li>
 *   <li>LoginTooLong disconnect: 30 s
 *       ({@code Minecraft.Client/PendingConnection.h:14} — {@code MAX_TICKS_BEFORE_LOGIN = 20 * 30}).</li>
 * </ul>
 *
 * <p>Thread model: one dedicated scheduler thread. For each tick it iterates every connection across
 * every {@link ConnectionThread} (via {@link ConnectionThread#connectionsSnapshot}), enqueues
 * outbound packets via {@link PlayerConnection#sendPacket} — which is thread-safe — and calls
 * {@link ConnectionThread#notifyOutboundReady} to wake the owning selector. The actual socket write
 * still happens on the owning {@code ConnectionThread}.
 */
public final class KeepAliveWatchdog {

    private static final Logger LOGGER = Logger.getLogger(KeepAliveWatchdog.class.getName());

    /** 1 Hz — check once per second. */
    private static final long TICK_PERIOD_MS = 1000L;

    /**
     * Keep-alive send threshold. Source: 20 ticks = 1 s
     * ({@code Minecraft.Client/PlayerConnection.cpp:128} — {@code tickCount - lastKeepAliveTick > 20}).
     *
     * <p>We subtract 50 ms of slop because the scheduler itself ticks at exactly 1 Hz: if we compared
     * against a full 1000 ms, minor drift (sometimes the tick fires 999.8 ms after the last, sometimes
     * 1000.2 ms) would cause keep-alives to alternate between 1 s and 2 s intervals. Using 950 ms
     * here means every scheduler tick where the connection is in {@code Play} and the last keep-alive
     * is at least 950 ms old will emit one — which, at a 1 Hz scheduler rate, is every tick.
     */
    public static final long KEEP_ALIVE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(950);

    /** In-Play timeout. Source: {@code MAX_TICKS_WITHOUT_INPUT = 20*60} = 60 s. */
    public static final long TIMEOUT_NANOS_DEFAULT = TimeUnit.SECONDS.toNanos(60);

    /** Pre-Play timeout. Source: {@code MAX_TICKS_BEFORE_LOGIN = 20*30} = 30 s. */
    public static final long LOGIN_TOO_LONG_NANOS_DEFAULT = TimeUnit.SECONDS.toNanos(30);

    private final NetworkManager networkManager;
    private final List<ConnectionThread> connectionThreads;
    private final ScheduledExecutorService executor;
    private final long timeoutNanos;
    private final long loginTooLongNanos;

    public KeepAliveWatchdog(NetworkManager networkManager, List<ConnectionThread> connectionThreads) {
        this(networkManager, connectionThreads, TIMEOUT_NANOS_DEFAULT, LOGIN_TOO_LONG_NANOS_DEFAULT);
    }

    /**
     * Test-only: customize the timeout and login-too-long thresholds so integration tests don't have
     * to wait a full minute. Production should always use the source-pinned defaults.
     */
    public KeepAliveWatchdog(NetworkManager networkManager, List<ConnectionThread> connectionThreads,
                             long timeoutNanos, long loginTooLongNanos) {
        this.networkManager = networkManager;
        this.connectionThreads = connectionThreads;
        this.timeoutNanos = timeoutNanos;
        this.loginTooLongNanos = loginTooLongNanos;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KeepAliveWatchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        executor.scheduleAtFixedRate(this::tick, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
        LOGGER.log(Level.INFO, "KeepAliveWatchdog started (tick period={0}ms)", TICK_PERIOD_MS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- main loop

    private void tick() {
        long now = System.nanoTime();
        try {
            for (ConnectionThread ct : connectionThreads) {
                for (PlayerConnection conn : ct.connectionsSnapshot()) {
                    if (conn.isDisconnected() || conn.isClosing()) continue;
                    inspect(conn, ct, now);
                }
            }
        } catch (Throwable t) {
            // A ScheduledExecutorService swallows uncaught exceptions and cancels future ticks.
            // Log loudly and rethrow only what we can — the watchdog going silent is a serious bug.
            LOGGER.log(Level.SEVERE, "KeepAliveWatchdog tick failed", t);
        }
    }

    private void inspect(PlayerConnection conn, ConnectionThread owner, long now) {
        ConnectionState state = conn.state();

        // --- LoginTooLong: connection stuck in Pending/Login for too long ---
        if (state != ConnectionState.Play) {
            long inHandshake = now - conn.acceptedAtNanos();
            if (inHandshake > loginTooLongNanos) {
                LOGGER.log(Level.INFO, "{0} LoginTooLong after {1}ms in state {2}",
                        new Object[] { conn.logTag(), inHandshake / 1_000_000L, state });
                sendDisconnect(conn, owner, NetworkConstants.DisconnectReason.LOGIN_TOO_LONG);
                return;
            }
        }

        // --- Timeout: no inbound bytes for the configured threshold ---
        long silent = now - conn.lastPacketReceivedAtNanos();
        if (silent > timeoutNanos) {
            LOGGER.log(Level.INFO, "{0} TimeOut after {1}ms of silence",
                    new Object[] { conn.logTag(), silent / 1_000_000L });
            sendDisconnect(conn, owner, NetworkConstants.DisconnectReason.TIME_OUT);
            return;
        }

        // --- KeepAlive: send one every 1s while in Play ---
        if (state == ConnectionState.Play) {
            long sinceLastKA = now - conn.lastKeepAliveSentAtNanos();
            if (sinceLastKA > KEEP_ALIVE_INTERVAL_NANOS) {
                int token = ThreadLocalRandom.current().nextInt();
                conn.recordKeepAliveSent(token, now);
                conn.sendPacket(new KeepAlivePacket(token));
                owner.notifyOutboundReady(conn);
                LOGGER.log(Level.FINEST, "{0} KeepAlive sent token=0x{1}",
                        new Object[] { conn.logTag(), Integer.toHexString(token) });
            }
        }
    }

    private void sendDisconnect(PlayerConnection conn, ConnectionThread owner, int reason) {
        conn.sendPacket(new DisconnectPacket(reason));
        conn.markClosing();
        owner.notifyOutboundReady(conn);
    }
}

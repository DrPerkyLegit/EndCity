package dev.endcity.network;

import dev.endcity.network.connection.PlayerConnection;
import dev.endcity.network.threads.BroadcastingThread;
import dev.endcity.network.threads.ConnectionThread;
import dev.endcity.network.threads.KeepAliveWatchdog;
import dev.endcity.network.utils.SmallIdPool;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Top-level owner of the transport layer: constructs and starts the accept thread and a configurable
 * number of {@link ConnectionThread}s, and routes each accepted socket to one of them.
 *
 * <p>{@link #handleIncomingConnection(SocketChannel)} is called from {@link BroadcastingThread} on
 * every accepted TCP connection and performs the full §3.2 server-initiated handshake:
 *
 * <ol>
 *   <li>Allocate a small ID from {@link SmallIdPool}. On failure, send the 6-byte reject frame
 *       (§3.2: {@code [0xFF][0xFF][4-byte BE eDisconnect_ServerFull]}) and close.</li>
 *   <li>On success, queue the 1-byte small ID for writing, construct a {@link PlayerConnection}, and
 *       hand it off to a {@link ConnectionThread} by round-robin.</li>
 * </ol>
 */
public final class NetworkManager {

    private static final Logger LOGGER = Logger.getLogger(NetworkManager.class.getName());

    private static final int CONNECTION_THREAD_COUNT = 4;

    private final BroadcastingThread broadcastThread;
    private final List<ConnectionThread> connectionThreads;
    private final KeepAliveWatchdog keepAliveWatchdog;
    private final SmallIdPool smallIdPool = new SmallIdPool();
    private final AtomicInteger roundRobin = new AtomicInteger();

    /**
     * Per-boot multiplayer session id. Source gets this from
     * {@code TelemetryManager->GetMultiplayerInstanceID()}; we generate a random non-zero int at
     * startup. Stays constant for the lifetime of the process.
     */
    private final int multiplayerInstanceId;

    /**
     * TODO M1.5: load from {@code server.properties}. Hardcoded for now so the M1 handshake can
     * produce a valid server-side {@code LoginPacket}. Matches the plan's sample config.
     */
    private static final int MAX_PLAYERS_TEMP = 8;

    public NetworkManager() {
        this(NetworkConstants.WIN64_NET_DEFAULT_PORT,
                KeepAliveWatchdog.TIMEOUT_NANOS_DEFAULT,
                KeepAliveWatchdog.LOGIN_TOO_LONG_NANOS_DEFAULT);
    }

    /** Test-only / advanced: use port 0 for an ephemeral port, then query {@link #boundPort()}. */
    public NetworkManager(int port) {
        this(port, KeepAliveWatchdog.TIMEOUT_NANOS_DEFAULT, KeepAliveWatchdog.LOGIN_TOO_LONG_NANOS_DEFAULT);
    }

    /**
     * Test-only: customize port and watchdog thresholds so integration tests don't have to wait the
     * full source-pinned 60 s / 30 s. Production uses the default public constructor.
     */
    public NetworkManager(int port, long timeoutNanos, long loginTooLongNanos) {
        // Non-zero random instance id. Using `| 1` to guarantee non-zero without biasing the
        // distribution noticeably — 0 seems like a value the client might treat specially.
        this.multiplayerInstanceId = ThreadLocalRandom.current().nextInt() | 1;
        this.broadcastThread = new BroadcastingThread(this, port);
        this.connectionThreads = new ArrayList<>(CONNECTION_THREAD_COUNT);
        try {
            for (int i = 0; i < CONNECTION_THREAD_COUNT; i++) {
                connectionThreads.add(new ConnectionThread(this, i));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to open selector for connection thread", e);
        }
        this.keepAliveWatchdog = new KeepAliveWatchdog(this, connectionThreads, timeoutNanos, loginTooLongNanos);
    }

    public SmallIdPool smallIdPool() { return smallIdPool; }
    public int multiplayerInstanceId() { return multiplayerInstanceId; }
    public int maxPlayers() { return MAX_PLAYERS_TEMP; }
    /** @return the port the accept socket actually bound to, or -1 if not yet bound. */
    public int boundPort() { return broadcastThread.boundPort(); }

    public void listen() {
        for (ConnectionThread t : connectionThreads) t.start();
        broadcastThread.start();
        keepAliveWatchdog.start();
        try { broadcastThread.awaitBound(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        LOGGER.log(Level.INFO, "NetworkManager listening on port {0} with {1} connection threads (pool capacity={2})",
                new Object[] {
                        broadcastThread.boundPort(),
                        CONNECTION_THREAD_COUNT,
                        smallIdPool.capacity()
                });
    }

    /**
     * Test-only: shut everything down. Interrupts the accept thread and every connection thread,
     * stops the keep-alive scheduler, and waits briefly for clean termination. Does <em>not</em>
     * attempt graceful per-connection disconnects — the {@code finally} block in each
     * {@code ConnectionThread.run()} handles that.
     */
    public void stop() {
        keepAliveWatchdog.stop();
        broadcastThread.interrupt();
        for (ConnectionThread t : connectionThreads) t.interrupt();
        try {
            broadcastThread.join(500);
            for (ConnectionThread t : connectionThreads) t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Called from {@link BroadcastingThread} for every accepted TCP connection. The socket arrives
     * non-blocking with {@code TCP_NODELAY} already set.
     */
    public void handleIncomingConnection(SocketChannel clientChannel) {
        SocketAddress remote = safeRemoteAddress(clientChannel);

        int smallId = smallIdPool.allocate();
        if (smallId == SmallIdPool.NO_ID) {
            LOGGER.log(Level.WARNING, "server full, rejecting connection from {0}", remote);
            sendRejectAndClose(clientChannel, NetworkConstants.DisconnectReason.SERVER_FULL);
            return;
        }

        PlayerConnection connection;
        try {
            connection = new PlayerConnection(clientChannel, smallId);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "failed to build PlayerConnection for smallId=" + smallId, e);
            smallIdPool.free(smallId);
            closeQuietly(clientChannel);
            return;
        }

        // Queue the 1-byte small-ID handshake for the owning ConnectionThread to send.
        ByteBuffer handshake = ByteBuffer.allocate(1);
        handshake.put((byte) smallId);
        handshake.flip();
        connection.enqueueOutbound(handshake);

        // Round-robin to one of the connection threads. Simple and good enough; the plan calls for it
        // explicitly (§3 M0 task 2).
        ConnectionThread target = connectionThreads.get(
                Math.floorMod(roundRobin.getAndIncrement(), connectionThreads.size()));
        target.register(connection);

        LOGGER.log(Level.INFO, "{0} handed to {1}",
                new Object[] { connection.logTag(), target.getName() });
    }

    // ---------------------------------------------------------------- reject frame

    /**
     * Build and send the 6-byte reject frame from {@code WinsockNetLayer.cpp::SendRejectWithReason}:
     * {@code [0xFF sentinel][0xFF DisconnectPacket id][4-byte big-endian reason]}. Best-effort — the
     * socket may already be gone. Always closes the channel afterward.
     */
    private static void sendRejectAndClose(SocketChannel channel, int reason) {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put(NetworkConstants.WIN64_SMALLID_REJECT);
        buf.put((byte) NetworkConstants.DISCONNECT_PACKET_ID);
        buf.putInt(reason); // ByteBuffer defaults to big-endian — what we want.
        buf.flip();

        // The socket is non-blocking, so write() may return 0 bytes. We retry briefly; a well-behaved
        // peer will drain the 6 bytes immediately. If not, we give up and close — the reject is
        // best-effort anyway.
        try {
            int attempts = 0;
            while (buf.hasRemaining() && attempts < 32) {
                int n = channel.write(buf);
                if (n == 0) {
                    try { Thread.sleep(1); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
                attempts++;
            }
        } catch (IOException e) {
            // Peer probably closed already. Nothing more we can do.
        }
        closeQuietly(channel);
    }

    // ---------------------------------------------------------------- helpers

    private static SocketAddress safeRemoteAddress(SocketChannel ch) {
        try { return ch.getRemoteAddress(); }
        catch (IOException e) { return null; }
    }

    private static void closeQuietly(SocketChannel ch) {
        try { ch.close(); } catch (IOException ignored) {}
    }
}

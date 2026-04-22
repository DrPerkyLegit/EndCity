package dev.endcity.network.threads;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.NetworkManager;
import dev.endcity.network.connection.PlayerConnection;
import dev.endcity.network.connection.ServerPacketListener;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.packets.handshake.DisconnectPacket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker thread that owns a {@link Selector} and a list of {@link PlayerConnection}s. It drives the
 * per-connection byte pump and, as of M1, the decode-and-dispatch pass after every successful read.
 *
 * <p>Registration is cross-thread: {@link BroadcastingThread} accepts a connection and calls
 * {@link NetworkManager#handleIncomingConnection} which in turn calls {@link #register}. Because
 * selectors can only be safely mutated from their owning thread, registrations are queued and flushed
 * at the top of each select loop iteration, and {@link Selector#wakeup()} is used to unblock the
 * selector so the newly enqueued connection is picked up promptly.
 */
public final class ConnectionThread extends Thread {

    private static final Logger LOGGER = Logger.getLogger(ConnectionThread.class.getName());

    private final NetworkManager networkManager;
    private final Selector selector;
    private final ConcurrentLinkedQueue<PlayerConnection> pendingRegistrations = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PlayerConnection> outboundReadyNotifications = new ConcurrentLinkedQueue<>();
    /**
     * Connections owned by this thread. {@link java.util.concurrent.CopyOnWriteArrayList} so the
     * keep-alive scheduler thread can iterate safely without synchronizing; writes (add on register,
     * remove on teardown) are infrequent enough that the copy cost is negligible.
     */
    private final java.util.concurrent.CopyOnWriteArrayList<PlayerConnection> connections = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<PlayerConnection, PacketListener> listeners = new IdentityHashMap<>();

    public ConnectionThread(NetworkManager networkManager, int index) throws IOException {
        super("ConnectionThread-" + index);
        this.networkManager = networkManager;
        this.selector = Selector.open();
        setDaemon(false);
    }

    /**
     * Hand a new connection to this thread. Safe to call from any thread; the selector is woken so
     * the registration is processed within one loop iteration.
     */
    public void register(PlayerConnection connection) {
        pendingRegistrations.offer(connection);
        selector.wakeup();
    }

    /**
     * Signal that {@code connection} has outbound bytes ready. Safe to call from any thread. The
     * next selector pass turns {@code OP_WRITE} back on for that channel and drains the queue.
     * Callers doing {@code connection.sendPacket(...)} from outside this thread should immediately
     * call this method.
     */
    public void notifyOutboundReady(PlayerConnection connection) {
        outboundReadyNotifications.offer(connection);
        selector.wakeup();
    }

    /** Current number of connections owned by this thread. Used by the round-robin balancer. */
    public int connectionCount() {
        return connections.size();
    }

    /**
     * Snapshot of all connections owned by this thread. Safe to call from any thread — the
     * underlying {@link java.util.concurrent.CopyOnWriteArrayList} provides a race-free view.
     */
    public java.util.List<PlayerConnection> connectionsSnapshot() {
        return java.util.List.copyOf(connections);
    }

    @Override
    public void run() {
        LOGGER.log(Level.INFO, "{0} started", getName());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // 1. Flush pending registrations and out-of-band OP_WRITE requests.
                drainPendingRegistrations();
                drainOutboundReadyNotifications();

                // 2. Block until something interesting happens (or wakeup() fires).
                selector.select();

                // 3. Dispatch I/O.
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (!key.isValid()) continue;

                    PlayerConnection conn = (PlayerConnection) key.attachment();
                    try {
                        if (key.isReadable()) handleRead(key, conn);
                        if (key.isValid() && key.isWritable()) handleWrite(key, conn);
                        // A packet handler (e.g. unexpected-packet gate) may have flagged the
                        // connection as closing. If outbound is drained, tear down now so the
                        // DisconnectPacket reaches the client before we close.
                        if (conn.isDisconnected()
                                || (conn.isClosing() && !conn.hasPendingOutbound())) {
                            teardown(conn, key);
                        }
                    } catch (CancelledKeyException ignored) {
                        // Key cancelled between isValid() and the op; teardown has already happened.
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, conn.logTag() + " I/O error, disconnecting: " + e.getMessage());
                        teardown(conn, key);
                    } catch (Packet.UnknownPacketIdException e) {
                        LOGGER.log(Level.WARNING, "{0} unknown/wrong-state packet id {1}; disconnecting",
                                new Object[] { conn.logTag(), e.id });
                        // Stream is desynced — the client's next bytes would be misinterpreted if we
                        // tried to keep reading. But the socket itself is still healthy, so a
                        // best-effort DisconnectPacket gives the client a clean reason to show the
                        // user instead of a socket-reset.
                        gracefulDisconnect(conn, key, NetworkConstants.DisconnectReason.UNEXPECTED_PACKET);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, getName() + " selector failure", e);
        } finally {
            // Server shutdown path. Try to notify every remaining connection cleanly before closing.
            for (PlayerConnection conn : new ArrayList<>(connections)) {
                flushGracefulClose(conn, NetworkConstants.DisconnectReason.CLOSED);
            }
            try { selector.close(); } catch (IOException ignored) {}
            LOGGER.log(Level.INFO, "{0} stopped", getName());
        }
    }

    // ---------------------------------------------------------------- registration

    private void drainPendingRegistrations() {
        PlayerConnection conn;
        while ((conn = pendingRegistrations.poll()) != null) {
            try {
                SelectionKey key = conn.channel().register(selector, SelectionKey.OP_READ, conn);
                connections.add(conn);
                conn.attachOwner(this);
                listeners.put(conn, new ServerPacketListener(conn, networkManager));
                if (conn.hasPendingOutbound()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
                LOGGER.log(Level.FINE, "{0} registered on {1}", new Object[] { conn.logTag(), getName() });
            } catch (ClosedChannelException e) {
                LOGGER.log(Level.WARNING, "{0} already closed at registration; discarding",
                        new Object[] { conn.logTag() });
                releaseSmallId(conn);
                closeQuietly(conn.channel());
            }
        }
    }

    private void drainOutboundReadyNotifications() {
        PlayerConnection conn;
        while ((conn = outboundReadyNotifications.poll()) != null) {
            SelectionKey key = conn.channel().keyFor(selector);
            if (key == null || !key.isValid()) continue;
            if (conn.hasPendingOutbound()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    // ---------------------------------------------------------------- read path

    private void handleRead(SelectionKey key, PlayerConnection conn)
            throws IOException, Packet.UnknownPacketIdException {
        // If we've already decided to close this connection (e.g. unexpected-packet → DisconnectPacket
        // queued), don't process any more inbound bytes — just drain the outbound and tear down.
        if (conn.isClosing()) return;

        SocketChannel ch = (SocketChannel) key.channel();

        if (!conn.ensureInboundCapacity(1)) {
            LOGGER.log(Level.WARNING, "{0} inbound buffer would exceed 4 MiB cap; disconnecting",
                    new Object[] { conn.logTag() });
            gracefulDisconnect(conn, key, NetworkConstants.DisconnectReason.OVERFLOW_);
            return;
        }

        int n = ch.read(conn.inbound());
        if (n < 0) {
            LOGGER.log(Level.INFO, "{0} peer closed", new Object[] { conn.logTag() });
            teardown(conn, key);
            return;
        }
        if (n > 0) {
            conn.markActivity();
            LOGGER.log(Level.FINEST, "{0} read {1} bytes (accum={2})",
                    new Object[] { conn.logTag(), n, conn.inbound().position() });

            PacketListener listener = listeners.get(conn);
            if (listener != null) {
                conn.decodeAndDispatch(listener);
                // Handlers may have enqueued outbound bytes; ensure OP_WRITE is on.
                if (key.isValid() && conn.hasPendingOutbound()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            }
        }
    }

    // ---------------------------------------------------------------- write path

    private void handleWrite(SelectionKey key, PlayerConnection conn) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        Deque<ByteBuffer> queue = conn.outbound();

        while (true) {
            ByteBuffer head;
            synchronized (queue) {
                head = queue.peekFirst();
            }
            if (head == null) break;

            ch.write(head);
            if (head.hasRemaining()) {
                return; // socket buffer full, resume on next OP_WRITE
            }
            synchronized (queue) {
                queue.pollFirst();
            }
        }

        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    // ---------------------------------------------------------------- teardown

    /**
     * Queue a {@link DisconnectPacket} and flag the connection as closing. The main select loop's
     * post-I/O check ({@code isClosing() && !hasPendingOutbound() → teardown}) will tear the
     * connection down once the disconnect bytes are on the wire, giving the client a clean reason
     * instead of an RST-flavoured mystery.
     *
     * <p>Used for in-thread decisions to drop a connection where the socket itself is still
     * healthy: inbound buffer overflow, unknown packet id. For already-broken sockets (EOF, I/O
     * error) we go straight to {@link #teardown} with no packet attempt.
     */
    private void gracefulDisconnect(PlayerConnection conn, SelectionKey key, int reason) {
        if (conn.isDisconnected() || conn.isClosing()) {
            // Already decided; fall through to the normal teardown check in the select loop.
            return;
        }
        conn.sendPacket(new DisconnectPacket(reason));
        conn.markClosing();
        if (key != null && key.isValid() && conn.hasPendingOutbound()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    /**
     * Best-effort synchronous send of a final {@link DisconnectPacket} followed by teardown. Used
     * only in the shutdown path, when the select loop has already exited and we can't rely on the
     * normal async drain. We attempt a bounded number of writes on the (non-blocking) socket; if
     * the client's receive buffer is full we give up and close the socket anyway.
     */
    private void flushGracefulClose(PlayerConnection conn, int reason) {
        SocketChannel ch = conn.channel();
        SelectionKey key = ch.keyFor(selector);
        if (!conn.isDisconnected() && !conn.isClosing()) {
            try {
                // Encode a DisconnectPacket inline and blast it at the socket. Don't bother with the
                // normal queue+selector dance — we're exiting.
                ByteBuffer frame = new DisconnectPacket(reason).encode();
                int attempts = 0;
                while (frame.hasRemaining() && attempts < 16) {
                    int n = ch.write(frame);
                    if (n == 0) {
                        try { Thread.sleep(1); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    attempts++;
                }
            } catch (IOException e) {
                // Client probably gone already. No action needed.
            }
        }
        teardown(conn, key);
    }

    private void teardown(PlayerConnection conn, SelectionKey key) {
        if (!conn.markDisconnected()) return;
        if (key != null) key.cancel();
        closeQuietly(conn.channel());
        releaseSmallId(conn);
        connections.remove(conn);
        listeners.remove(conn);
        LOGGER.log(Level.INFO, "{0} torn down (state={1}, pool_free={2})",
                new Object[] { conn.logTag(), conn.state(), networkManager.smallIdPool().available() });
    }

    private void releaseSmallId(PlayerConnection conn) {
        int id = conn.smallId();
        if (id >= NetworkConstants.XUSER_MAX_COUNT && id < NetworkConstants.MINECRAFT_NET_MAX_PLAYERS) {
            try {
                networkManager.smallIdPool().free(id);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, conn.logTag() + " failed to free small ID: " + e.getMessage());
            }
        }
    }

    private static void closeQuietly(SocketChannel ch) {
        try { ch.close(); } catch (IOException ignored) {}
    }
}

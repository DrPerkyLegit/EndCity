package dev.endcity.network.threads;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.NetworkManager;
import dev.endcity.network.connection.PlayerConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker thread that owns a {@link Selector} and a list of {@link PlayerConnection}s. It drives the
 * per-connection byte pump: reads from each readable socket into that connection's inbound buffer,
 * and writes queued outbound buffers back out when the socket is writable.
 *
 * <p>M0 scope: transport only. No packet decoding happens here — that lands in M1.
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
    private final List<PlayerConnection> connections = new ArrayList<>();

    public ConnectionThread(NetworkManager networkManager, int index) throws IOException {
        super("ConnectionThread-" + index);
        this.networkManager = networkManager;
        this.selector = Selector.open();
        setDaemon(false);
    }

    /**
     * Hand a new connection to this thread. Safe to call from any thread; the selector is woken so the
     * registration is processed within one loop iteration.
     */
    public void register(PlayerConnection connection) {
        pendingRegistrations.offer(connection);
        selector.wakeup();
    }

    /** Current number of connections owned by this thread. Used by the round-robin balancer. */
    public int connectionCount() {
        // Read without a lock: this is only consumed by handleIncomingConnection for load-balancing hints,
        // where racing by one is harmless. Strict count would require a volatile counter.
        return connections.size();
    }

    @Override
    public void run() {
        LOGGER.log(Level.INFO, "{0} started", getName());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // 1. Flush pending registrations from the accept thread.
                drainPendingRegistrations();

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
                    } catch (CancelledKeyException ignored) {
                        // The key was cancelled between isValid() and the op; teardown has already happened.
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, conn.logTag() + " I/O error, disconnecting: " + e.getMessage());
                        teardown(conn, key);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, getName() + " selector failure", e);
        } finally {
            // Close down every owned connection on thread exit.
            for (PlayerConnection conn : new ArrayList<>(connections)) {
                teardown(conn, conn.channel().keyFor(selector));
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
                // If the connection has buffered outbound bytes (e.g. the small-ID handshake byte was
                // enqueued before registration), make sure OP_WRITE is on.
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

    // ---------------------------------------------------------------- read path

    private void handleRead(SelectionKey key, PlayerConnection conn) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();

        if (!conn.ensureInboundCapacity(1)) {
            LOGGER.log(Level.WARNING, "{0} inbound buffer would exceed 4 MiB cap; disconnecting",
                    new Object[] { conn.logTag() });
            teardown(conn, key);
            return;
        }

        int n = ch.read(conn.inbound());
        if (n < 0) {
            // Clean EOF from the client side.
            LOGGER.log(Level.INFO, "{0} peer closed", new Object[] { conn.logTag() });
            teardown(conn, key);
            return;
        }
        if (n > 0) {
            conn.markActivity();
            LOGGER.log(Level.FINEST, "{0} read {1} bytes (accum={2})",
                    new Object[] { conn.logTag(), n, conn.inbound().position() });
            // M0: no packet decoding. Bytes sit in the accumulator for M1 to consume.
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
                // Socket buffer is full; come back when writable.
                return;
            }
            synchronized (queue) {
                queue.pollFirst();
            }
        }

        // Queue is drained — stop asking for OP_WRITE until something new is enqueued.
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    // ---------------------------------------------------------------- teardown

    private void teardown(PlayerConnection conn, SelectionKey key) {
        if (!conn.markDisconnected()) return; // already torn down
        if (key != null) key.cancel();
        closeQuietly(conn.channel());
        releaseSmallId(conn);
        connections.remove(conn);
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

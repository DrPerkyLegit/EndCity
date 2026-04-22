package dev.endcity.network.connection;

import dev.endcity.network.NetworkConstants;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a single connected LCE Win64 client from the moment a small ID has been assigned until
 * the socket is closed and the ID is returned to the pool.
 *
 * <p>State machine (linear + terminal): {@code Pending -> Login -> Play}, plus a terminal
 * {@code disconnected} flag. See {@code EndCity_Design.md} §3.2 / §4.3 and {@code PLAN.md} §2 rule 8.
 *
 * <p>Thread model: each {@code PlayerConnection} is owned by exactly one
 * {@code ConnectionThread} after registration. The inbound buffer and outbound queue must only be
 * touched from that owning thread, with the exception of {@link #enqueueOutbound(ByteBuffer)} which
 * may be called from any thread (it synchronizes on the queue).
 */
public final class PlayerConnection {

    private static final Logger LOGGER = Logger.getLogger(PlayerConnection.class.getName());

    private final SocketChannel channel;
    private final int smallId;
    private final String remoteAddress;
    private final String logTag;

    private volatile ConnectionState state = ConnectionState.Pending;
    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    /**
     * Per-connection inbound byte accumulator. Starts at {@link NetworkConstants#INBOUND_INITIAL_CAPACITY}
     * (8 KiB) and grows up to {@link NetworkConstants#WIN64_NET_MAX_PACKET_SIZE} (4 MiB).
     *
     * <p>Invariant: kept in <em>write mode</em> between reads. The {@code ConnectionThread} flips it to
     * read mode when scanning for complete packets (M1+) and flips it back with {@code compact()} after
     * consumed bytes are drained.
     */
    private ByteBuffer inbound = ByteBuffer.allocate(NetworkConstants.INBOUND_INITIAL_CAPACITY);

    /**
     * Queue of already-serialized outbound byte buffers ready to write to the socket. Each buffer is
     * in read mode (position=0, limit=length) when enqueued. M0 only pushes raw byte frames onto this
     * queue (the small-ID handshake byte); M1 adds serialized packets.
     */
    private final Deque<ByteBuffer> outbound = new ArrayDeque<>();

    /** Wall-clock time (via {@code System.nanoTime()}) of the most recent inbound byte. Used by the M1 keep-alive watchdog. */
    private volatile long lastPacketReceivedAtNanos;

    public PlayerConnection(SocketChannel channel, int smallId) {
        this.channel = channel;
        this.smallId = smallId;
        this.remoteAddress = readRemoteAddress(channel);
        this.logTag = "[conn smallId=" + smallId + " remote=" + remoteAddress + "]";
        this.lastPacketReceivedAtNanos = System.nanoTime();
        LOGGER.log(Level.INFO, "{0} accepted, state=Pending", logTag);
    }

    private static String readRemoteAddress(SocketChannel channel) {
        try {
            SocketAddress addr = channel.getRemoteAddress();
            return (addr == null) ? "?" : addr.toString();
        } catch (IOException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ accessors

    public SocketChannel channel() { return channel; }
    public int smallId() { return smallId; }
    public String remoteAddress() { return remoteAddress; }
    public String logTag() { return logTag; }
    public ConnectionState state() { return state; }
    public boolean isDisconnected() { return disconnected.get(); }
    public ByteBuffer inbound() { return inbound; }
    public Deque<ByteBuffer> outbound() { return outbound; }
    public long lastPacketReceivedAtNanos() { return lastPacketReceivedAtNanos; }

    public void markActivity() { this.lastPacketReceivedAtNanos = System.nanoTime(); }

    public void transitionTo(ConnectionState next) {
        ConnectionState prev = this.state;
        this.state = next;
        LOGGER.log(Level.INFO, "{0} state {1} -> {2}", new Object[] { logTag, prev, next });
    }

    // ------------------------------------------------------------------ inbound buffer management

    /**
     * Ensure the inbound buffer has at least {@code additional} bytes of free space, growing up to the
     * 4 MiB cap. Caller must have the buffer in write mode. Returns {@code false} if the cap would be
     * exceeded — in that case the caller must disconnect with {@code eDisconnect_Overflow}.
     */
    public boolean ensureInboundCapacity(int additional) {
        if (inbound.remaining() >= additional) return true;

        int needed = inbound.position() + additional;
        if (needed > NetworkConstants.WIN64_NET_MAX_PACKET_SIZE) return false;

        int newCap = inbound.capacity();
        while (newCap < needed) newCap = Math.min(newCap * 2, NetworkConstants.WIN64_NET_MAX_PACKET_SIZE);

        ByteBuffer grown = ByteBuffer.allocate(newCap);
        inbound.flip();
        grown.put(inbound);
        this.inbound = grown;
        return true;
    }

    // ------------------------------------------------------------------ outbound queue

    /**
     * Append a buffer (in read mode, position=0) to the outbound queue. Safe to call from any thread;
     * the owning {@code ConnectionThread} will drain it on the next selector pass after being woken.
     */
    public void enqueueOutbound(ByteBuffer buffer) {
        synchronized (outbound) {
            outbound.addLast(buffer);
        }
    }

    public boolean hasPendingOutbound() {
        synchronized (outbound) {
            return !outbound.isEmpty();
        }
    }

    // ------------------------------------------------------------------ teardown

    /**
     * Mark this connection as disconnected. Idempotent; returns {@code true} only on the first call so
     * callers can gate teardown logic (close channel, free small ID, remove from thread list) on the
     * return value.
     */
    public boolean markDisconnected() {
        return disconnected.compareAndSet(false, true);
    }
}

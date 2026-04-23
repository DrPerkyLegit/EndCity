package dev.endcity.network.connection;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;

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
 * {@code ConnectionThread} after registration. The inbound buffer is only touched from that owning
 * thread. The outbound queue is touched from the owning thread (for draining) and potentially
 * other threads (for enqueuing); all access is synchronized on the queue object.
 */
public final class PlayerConnection {

    private static final Logger LOGGER = Logger.getLogger(PlayerConnection.class.getName());

    private final SocketChannel channel;
    private final int smallId;
    private final String remoteAddress;
    private final String logTag;

    private volatile ConnectionState state = ConnectionState.Pending;
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /**
     * Per-connection inbound byte accumulator. Starts at {@link NetworkConstants#INBOUND_INITIAL_CAPACITY}
     * (8 KiB) and grows up to {@link NetworkConstants#WIN64_NET_MAX_PACKET_SIZE} (4 MiB).
     *
     * <p>Invariant: kept in <em>write mode</em> between calls to {@link #decodeAndDispatch}. The
     * decoder flips to read mode internally and restores write mode before returning.
     */
    private ByteBuffer inbound = ByteBuffer.allocate(NetworkConstants.INBOUND_INITIAL_CAPACITY);

    /**
     * Queue of already-serialized outbound byte buffers ready to write to the socket. Each buffer is
     * in read mode (position=0, limit=length) when enqueued.
     */
    private final Deque<ByteBuffer> outbound = new ArrayDeque<>();

    /** Wall-clock time (via {@code System.nanoTime()}) of the most recent inbound byte. Used by the M1 keep-alive watchdog. */
    private volatile long lastPacketReceivedAtNanos;

    /** Timestamp of connection acceptance; used for the M1 LoginTooLong watchdog. */
    private final long acceptedAtNanos;

    // ---------------------------------------------------------------- TEMPORARY: handshake byte log (M2 investigation)
    //
    // Accumulates every byte the client sends us up to HANDSHAKE_BYTE_LOG_CAP, so we can hex-dump
    // the stream when decoding fails during Pending/Login. Remove this block + the corresponding
    // appendToByteLog() callsite in ConnectionThread.handleRead once the PreLogin desync is fixed.
    private static final int HANDSHAKE_BYTE_LOG_CAP = 2048;
    private final byte[] handshakeByteLog = new byte[HANDSHAKE_BYTE_LOG_CAP];
    private int handshakeByteLogPos;

    /** Append up to {@code HANDSHAKE_BYTE_LOG_CAP} bytes; silently drops excess once full. */
    public void appendToByteLog(ByteBuffer src, int startPosition, int endPosition) {
        int len = endPosition - startPosition;
        if (len <= 0) return;
        int space = HANDSHAKE_BYTE_LOG_CAP - handshakeByteLogPos;
        if (space <= 0) return;
        int copyLen = Math.min(len, space);
        for (int i = 0; i < copyLen; i++) {
            handshakeByteLog[handshakeByteLogPos + i] = src.get(startPosition + i);
        }
        handshakeByteLogPos += copyLen;
    }

    /** Hex-dump the byte log in {@code offset  hh hh hh ... | ascii} rows of 16. */
    public String byteLogHexDump() {
        if (handshakeByteLogPos == 0) return "(no bytes recorded)";
        StringBuilder sb = new StringBuilder(handshakeByteLogPos * 4);
        sb.append("inbound bytes (").append(handshakeByteLogPos).append(" total):\n");
        for (int i = 0; i < handshakeByteLogPos; i += 16) {
            sb.append(String.format("  %04x  ", i));
            int rowEnd = Math.min(i + 16, handshakeByteLogPos);
            for (int j = i; j < i + 16; j++) {
                if (j < rowEnd) sb.append(String.format("%02x ", handshakeByteLog[j] & 0xFF));
                else sb.append("   ");
            }
            sb.append(" |");
            for (int j = i; j < rowEnd; j++) {
                int b = handshakeByteLog[j] & 0xFF;
                sb.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            }
            sb.append("|\n");
        }
        return sb.toString();
    }
    // ---------------------------------------------------------------- end temporary block

    /**
     * The {@code ConnectionThread} currently owning this connection's selector key. Set exactly once
     * during registration and read by the keep-alive scheduler to route {@code notifyOutboundReady}
     * calls. Package-typed as {@link Object} to avoid a cyclic package import; callers should cast.
     *
     * <p>Setting is done via {@link #attachOwner} and visibility is guaranteed via the write to
     * a {@code volatile} field.
     */
    private volatile Object owner;

    // ---------------------------------------------------------------- keep-alive bookkeeping (chunk 8)

    /**
     * Timestamp (nanos) at which we last queued a {@code KeepAlivePacket} to this connection. Only
     * touched by the keep-alive scheduler thread; no cross-thread synchronization needed.
     */
    private long lastKeepAliveSentAtNanos;

    /**
     * Random Int token of the last-sent {@code KeepAlivePacket}. Compared against echoed keep-alive
     * tokens to compute round-trip latency (source: {@code Minecraft.Client/PlayerConnection.cpp:1370}).
     * Only read/written by the {@link #state()}={@code Play} state machine.
     */
    private int lastKeepAliveToken;

    public PlayerConnection(SocketChannel channel, int smallId) {
        this.channel = channel;
        this.smallId = smallId;
        this.remoteAddress = readRemoteAddress(channel);
        this.logTag = "[conn smallId=" + smallId + " remote=" + remoteAddress + "]";
        this.acceptedAtNanos = System.nanoTime();
        this.lastPacketReceivedAtNanos = this.acceptedAtNanos;
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
    public boolean isClosing() { return closing.get(); }
    public ByteBuffer inbound() { return inbound; }
    public Deque<ByteBuffer> outbound() { return outbound; }
    public long lastPacketReceivedAtNanos() { return lastPacketReceivedAtNanos; }
    public long acceptedAtNanos() { return acceptedAtNanos; }
    public Object owner() { return owner; }
    public void attachOwner(Object owner) { this.owner = owner; }
    public long lastKeepAliveSentAtNanos() { return lastKeepAliveSentAtNanos; }
    public int lastKeepAliveToken() { return lastKeepAliveToken; }
    public void recordKeepAliveSent(int token, long atNanos) {
        this.lastKeepAliveToken = token;
        this.lastKeepAliveSentAtNanos = atNanos;
    }

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

    /**
     * Decode as many complete packets as the inbound buffer currently holds and dispatch each one to
     * {@code listener}. Stops when:
     * <ul>
     *   <li>the buffer is drained; or</li>
     *   <li>a partial packet is encountered (buffer underflow): the remaining bytes are preserved
     *       for the next call; or</li>
     *   <li>a handler flags the connection as {@link #isClosing() closing} — further buffered bytes
     *       are not dispatched (interpreting them against a connection we've already decided to
     *       kick can produce bogus IOExceptions on pipelined wrong-state streams); or</li>
     *   <li>a dispatch throws or an unknown packet id arrives: propagated to the caller which
     *       should disconnect.</li>
     * </ul>
     *
     * <p>Called by the owning {@code ConnectionThread} after every successful socket read.
     */
    public void decodeAndDispatch(PacketListener listener)
            throws IOException, Packet.UnknownPacketIdException {
        // Flip to read mode. position() = 0, limit() = bytes-written.
        inbound.flip();
        try {
            while (true) {
                Packet p = Packet.tryDecode(inbound);
                if (p == null) break; // partial frame, wait for more bytes
                p.handle(listener);
                // A handler may have queued a DisconnectPacket and flagged us as closing. Don't
                // dispatch any further buffered packets — the connection is about to be torn down
                // and we don't want to interpret more bytes (which might be part of a pipelined
                // stream) and risk throwing a misleading decode error after the real root cause.
                if (isClosing()) break;
            }
        } finally {
            // Shuffle any un-consumed bytes to the front and restore write mode (position = #leftover,
            // limit = capacity). Subsequent reads append after the leftover bytes.
            inbound.compact();
        }
    }

    // ------------------------------------------------------------------ outbound queue

    /**
     * Append a raw byte buffer (in read mode, position=0, limit=length) to the outbound queue.
     * Safe to call from any thread.
     */
    public void enqueueOutbound(ByteBuffer buffer) {
        synchronized (outbound) {
            outbound.addLast(buffer);
        }
    }

    /**
     * Serialize {@code packet} with framing and enqueue it for sending. Safe to call from any
     * thread; the owning {@code ConnectionThread} picks up the enqueued buffer on the next selector
     * pass (the caller should also {@code selector.wakeup()} if not already in that thread — see
     * {@link dev.endcity.network.threads.ConnectionThread#notifyOutboundReady}).
     */
    public void sendPacket(Packet packet) {
        try {
            ByteBuffer encoded = packet.encode();
            enqueueOutbound(encoded);
            LOGGER.log(Level.FINE, "{0} queued packet id={1} ({2} bytes)",
                    new Object[] { logTag, packet.getId(), encoded.remaining() });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, logTag + " failed to encode packet id=" + packet.getId(), e);
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

    /**
     * Flag this connection as "closing": the ConnectionThread should drain pending outbound bytes
     * (typically a final {@code DisconnectPacket}), then tear down. Idempotent; returns {@code true}
     * only on the first call. Call {@link #markDisconnected()} afterwards when the actual teardown
     * happens.
     */
    public boolean markClosing() {
        return closing.compareAndSet(false, true);
    }
}

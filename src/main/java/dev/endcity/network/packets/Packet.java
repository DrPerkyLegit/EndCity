package dev.endcity.network.packets;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Wire-level packet base class. Mirrors {@code Minecraft.World/Packet.h} — every concrete subclass
 * knows its own ID and how to serialize/deserialize its body.
 *
 * <h2>Framing</h2>
 * {@code [4-byte BE length][1 byte id][body]}. The length field is the size of
 * {@code [id][body]} — i.e. the byte count that follows the length prefix itself.
 *
 * <p>The length prefix is NOT visible in the source's {@code Packet::readPacket} /
 * {@code writePacket} — those operate on the already-framed inner stream. It's emitted by the
 * Win64 transport shim ({@code Minecraft.Client/Windows64/Network/WinsockNetLayer.cpp::SendOnSocket}):
 * <pre>
 *   BYTE header[4] = { dataSize &gt;&gt; 24, dataSize &gt;&gt; 16, dataSize &gt;&gt; 8, dataSize };
 *   send(sock, header, 4, 0);
 *   send(sock, packetBody, dataSize, 0);
 * </pre>
 * The matching recv side ({@code RecvThreadProc}) reads the 4-byte header with {@code RecvExact},
 * then reads exactly {@code packetSize} bytes for the body. The length frame is there because the
 * original protocol was Xbox XRNM which delivers discrete message boundaries; on TCP we have to
 * fake the boundary back with a length prefix.
 *
 * <p>The reference server implementation at
 * {@code .MinecraftLegacyEdition/server/src/core/TcpLayer.cpp::SendOnSocket} confirms the same
 * behaviour.
 *
 * <p>Subclasses are mutable data carriers: the framework constructs an empty instance, calls
 * {@link #read(PacketBuffer)} to populate the body fields, and then hands it to a
 * {@link PacketListener} via {@link #handle(PacketListener)}.
 */
public abstract class Packet {

    /** Packet id as it appears on the wire (0–255). */
    public abstract int getId();

    /** Populate this packet from the buffer. May throw if the body is malformed. */
    public abstract void read(PacketBuffer buf) throws IOException;

    /** Serialize this packet's body to the buffer. */
    public abstract void write(PacketBuffer buf) throws IOException;

    /** Dispatch to the appropriate {@code handleXxx} method on the listener. */
    public abstract void handle(PacketListener listener) throws IOException;

    // -------------------------------------------------------------- framing helpers

    /**
     * Serialize this packet with framing ({@code [4-byte BE length][1 byte id][body]}) into a fresh
     * read-mode {@link ByteBuffer} ready to enqueue on a {@code PlayerConnection}'s outbound.
     */
    public ByteBuffer encode() throws IOException {
        // Write [id][body] into an inner buffer first so we know the length.
        PacketBuffer inner = PacketBuffer.allocate(1024);
        inner.writeByte(getId());
        write(inner);
        byte[] body = inner.toByteArray();

        // Allocate the final buffer = 4-byte length + body.
        ByteBuffer out = ByteBuffer.allocate(4 + body.length);
        out.putInt(body.length);
        out.put(body);
        out.flip();
        return out;
    }

    /**
     * Attempt to decode a single framed packet from {@code src} (in read mode). On success returns
     * the populated packet, advancing {@code src.position()} past the length prefix + body. On
     * partial-frame (not enough bytes for either the prefix or the declared body length), rewinds
     * {@code src.position()} to where it was on entry and returns {@code null}: the caller should
     * stop decoding and wait for more bytes.
     */
    public static Packet tryDecode(ByteBuffer src) throws IOException, UnknownPacketIdException {
        int startPos = src.position();

        // Need at least 4 bytes for the length prefix.
        if (src.remaining() < 4) return null;

        int packetSize = src.getInt();
        if (packetSize <= 0 || packetSize > NetworkConstants.WIN64_NET_MAX_PACKET_SIZE) {
            // Don't rewind — the stream is irrecoverably desynced. Throw so the connection can be
            // disconnected with Overflow / UnexpectedPacket.
            throw new IOException("invalid framed packet size: " + packetSize);
        }

        if (src.remaining() < packetSize) {
            // Body hasn't fully arrived yet. Rewind to before the length prefix and wait.
            src.position(startPos);
            return null;
        }

        // We have [id][body] of exactly packetSize bytes. Constrain the view so body parsers can't
        // overread into whatever comes next, and so underread leaves a deterministic slack to
        // detect malformed packets.
        int bodyStart = src.position();
        int bodyEnd = bodyStart + packetSize;
        int savedLimit = src.limit();
        src.limit(bodyEnd);

        try {
            int id = src.get() & 0xFF;
            Packet instance = Packets.create(id);
            if (instance == null || !Packets.isServerReceived(id)) {
                throw new UnknownPacketIdException(id);
            }
            try {
                instance.read(PacketBuffer.wrap(src));
            } catch (BufferUnderflowException underflow) {
                // The declared packet size was correct for the framing layer but the body parser
                // ran off the end. That means the body was malformed — the framing guaranteed us
                // N bytes and the parser couldn't consume them coherently. Treat as a protocol
                // error, not a partial frame (because the frame IS complete).
                throw new IOException("body parser underran declared packet size " + packetSize
                        + " for id " + id);
            }
            // Always advance to the end of the framed body, even if read() consumed fewer bytes.
            // (Same safety valve as the source's framing contract — drop any trailing slack rather
            // than let it leak into the next packet's parse.)
            src.position(bodyEnd);
            return instance;
        } finally {
            src.limit(savedLimit);
        }
    }

    /**
     * Thrown when an incoming packet id is not in the {@link Packets#isServerReceived server-
     * received} set. The decoder catches this and disconnects the offender with
     * {@code eDisconnect_UnexpectedPacket}.
     */
    public static final class UnknownPacketIdException extends Exception {
        public final int id;
        public UnknownPacketIdException(int id) {
            super("unknown or non-server-received packet id: " + id);
            this.id = id;
        }
    }
}

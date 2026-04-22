package dev.endcity.network.packets;

import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Wire-level packet base class. Mirrors {@code Minecraft.World/Packet.h} — every concrete subclass
 * knows its own ID and how to serialize/deserialize its body.
 *
 * <p>Framing: {@code [1 byte id][body]}. No length prefix, no trailer. See
 * {@code EndCity_Design.md} §3.4.
 *
 * <p>Subclasses are expected to be mutable data carriers: the framework constructs an empty
 * instance, calls {@link #read(PacketBuffer)} to populate it, and then hands it to a
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
     * Serialize this packet with framing ({@code [1 byte id][body]}) into a fresh read-mode
     * {@link ByteBuffer} ready to enqueue on a {@code PlayerConnection}'s outbound.
     *
     * <p>Mirrors {@code Minecraft.World/Packet.cpp::writePacket}: {@code dos->write(packet->getId());
     * packet->write(dos);}.
     */
    public ByteBuffer encode() throws IOException {
        // Start with a small buffer; grow if needed. Most handshake packets are well under 1 KiB.
        PacketBuffer buf = PacketBuffer.allocate(1024);
        buf.writeByte(getId());
        write(buf);
        byte[] bytes = buf.toByteArray();
        return ByteBuffer.wrap(bytes);
    }

    /**
     * Attempt to decode a single packet from {@code src} (in read mode). On success returns the
     * populated packet, advancing {@code src.position()} past the consumed bytes. On partial-frame
     * (buffer underflow), rewinds {@code src.position()} to where it was on entry and returns
     * {@code null}: the caller should stop decoding and wait for more bytes.
     *
     * <p>Mirrors the outer shape of {@code Minecraft.World/Packet.cpp::readPacket} but diverges on
     * unknown / wrong-state packet ids: the source silently returns {@code nullptr} (orphaning body
     * bytes on a blocking stream, where it's harmless), we throw a dedicated exception so the
     * caller can disconnect rather than desync.
     */
    public static Packet tryDecode(ByteBuffer src) throws IOException, UnknownPacketIdException {
        int startPos = src.position();
        if (!src.hasRemaining()) return null;

        int id = src.get() & 0xFF;
        Packet instance = Packets.create(id);
        if (instance == null || !Packets.isServerReceived(id)) {
            throw new UnknownPacketIdException(id);
        }

        try {
            instance.read(PacketBuffer.wrap(src));
            return instance;
        } catch (BufferUnderflowException underflow) {
            // Partial frame — rewind. The read() call may have consumed some bytes; reset to the
            // start-of-packet position so the next attempt sees the id byte again.
            src.position(startPos);
            return null;
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

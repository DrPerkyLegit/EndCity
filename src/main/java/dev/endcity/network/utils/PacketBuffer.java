package dev.endcity.network.utils;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Wire-level I/O helper. Wraps a {@link ByteBuffer} and provides the exact set of type-level
 * read/write operations used by the LCE Win64 protocol, matching {@code DataInputStream} /
 * {@code DataOutputStream} and 4J's {@code Packet::readUtf} / {@code writeUtf} / {@code readBytes} /
 * {@code writeBytes} in the C++ source.
 *
 * <p>All multi-byte values are big-endian (Java's {@link ByteBuffer} default). Do not change the
 * byte order.
 *
 * <h2>Read semantics</h2>
 * A {@code PacketBuffer} in read mode wraps a buffer positioned at the start of a packet body. If
 * the body is incomplete, any {@code read*} method will raise {@link BufferUnderflowException}. The
 * caller (the decode loop on {@link dev.endcity.network.connection.PlayerConnection}) is expected to
 * {@link ByteBuffer#mark() mark} before attempting to decode a packet and {@link ByteBuffer#reset()
 * reset} on underflow to wait for more bytes.
 *
 * <p>Protocol-level validation failures — oversized strings or byte arrays — raise
 * {@link IOException}. The decode loop converts these into a disconnect with
 * {@code eDisconnect_Overflow}, rather than silently continuing (which would desync the stream).
 *
 * <h2>Write semantics</h2>
 * A {@code PacketBuffer} in write mode wraps a buffer to which bytes are appended. Writes advance
 * the position; the caller flips to read mode (or calls {@link #toByteArray()}) to recover the
 * encoded body.
 */
public final class PacketBuffer {

    private final ByteBuffer buf;

    public PacketBuffer(ByteBuffer buf) {
        this.buf = buf;
    }

    /** Allocate a new write-mode buffer with the given initial capacity. */
    public static PacketBuffer allocate(int capacity) {
        return new PacketBuffer(ByteBuffer.allocate(capacity));
    }

    /** Wrap an existing buffer (in whatever mode it's already in). */
    public static PacketBuffer wrap(ByteBuffer existing) {
        return new PacketBuffer(existing);
    }

    public ByteBuffer raw() { return buf; }

    // -------------------------------------------------------------- primitives (match DataInput/Output)

    public byte readByte()        { return buf.get(); }
    public boolean readBoolean()  { return buf.get() != 0; }
    public short readShort()      { return buf.getShort(); }
    /** Unsigned-short read. Useful for packed-nibble count headers (e.g. {@code ChatPacket}). */
    public int readUnsignedShort(){ return buf.getShort() & 0xFFFF; }
    public int readInt()          { return buf.getInt(); }
    public long readLong()        { return buf.getLong(); }
    public float readFloat()      { return buf.getFloat(); }
    public double readDouble()    { return buf.getDouble(); }

    /**
     * Player UID. On Win64 this is wire-identical to a signed 64-bit big-endian long — see
     * {@code Minecraft.World/DataInputStream.cpp::readPlayerUID} ({@code returnValue = readLong();}
     * in the non-PS3/non-Durango branch). PS3/Vita serialize as a raw struct and Xbox One as a UTF
     * string; EndCity does not support those platforms.
     */
    public long readPlayerUid()   { return buf.getLong(); }

    public void writeByte(int v)      { buf.put((byte) v); }
    public void writeBoolean(boolean v){ buf.put((byte) (v ? 1 : 0)); }
    public void writeShort(int v)     { buf.putShort((short) v); }
    public void writeInt(int v)       { buf.putInt(v); }
    public void writeLong(long v)     { buf.putLong(v); }
    public void writeFloat(float v)   { buf.putFloat(v); }
    public void writeDouble(double v) { buf.putDouble(v); }
    public void writePlayerUid(long v){ buf.putLong(v); }

    // -------------------------------------------------------------- LCE UTF (§3.6)

    /**
     * Read an LCE-style UTF string: {@code [Short charCount][N × 2-byte UTF-16BE wchar_t]}.
     *
     * <p>Source: {@code Minecraft.World/Packet.cpp::readUtf} (line 367+). The source silently
     * returns an empty string on {@code length > maxLength} or {@code length <= 0} and does NOT
     * consume body bytes in that case, guaranteeing a stream desync on the next field. We diverge:
     * a string declared over the max or negative is a protocol violation and we signal it with
     * {@link IOException}, so the caller can disconnect cleanly with {@code eDisconnect_Overflow}.
     * Zero-length is legal and returns the empty string (source returns empty there too).
     */
    public String readLceUtf(int maxChars) throws IOException {
        short charCount = readShort();
        if (charCount < 0) {
            throw new IOException("LCE UTF length negative: " + charCount);
        }
        if (charCount > maxChars) {
            throw new IOException("LCE UTF length " + charCount + " exceeds max " + maxChars);
        }
        if (charCount == 0) return "";

        char[] chars = new char[charCount];
        for (int i = 0; i < charCount; i++) {
            // readChar on a UTF-16BE wchar_t = read two bytes big-endian into a char.
            chars[i] = buf.getChar();
        }
        return new String(chars);
    }

    /**
     * Write an LCE-style UTF string: {@code [Short charCount][N × 2-byte UTF-16BE wchar_t]}.
     *
     * <p>Source: {@code Packet.cpp::writeUtf} — {@code writeShort(value.length()); writeChars(value);}.
     * Java {@code String} is already UTF-16; we just need to emit each {@code char} as 2 big-endian
     * bytes, which {@link ByteBuffer#putChar(char)} does.
     *
     * @throws IOException if the string exceeds {@code Short.MAX_VALUE} characters (wire length is a
     *         signed short). Not hit by anything we emit — our strings are all bounded by the
     *         design-doc max lengths — but enforced anyway to catch bugs loudly.
     */
    public void writeLceUtf(String value) throws IOException {
        int len = value.length();
        if (len > Short.MAX_VALUE) {
            throw new IOException("LCE UTF length " + len + " exceeds Short.MAX_VALUE");
        }
        writeShort(len);
        for (int i = 0; i < len; i++) {
            buf.putChar(value.charAt(i));
        }
    }

    // -------------------------------------------------------------- byte array (Packet::readBytes/writeBytes)

    /**
     * Read a byte array framed as {@code [Short length][length bytes]}.
     *
     * <p>Source: {@code Packet.cpp::readBytes} — {@code size = readShort(); if (size < 0) return empty;
     * readFully(bytes);}. Negative-length is treated as an error by the source (it returns an empty
     * array); we make it loud via {@link IOException} for the same stream-desync reason as
     * {@link #readLceUtf}.
     */
    public byte[] readByteArray() throws IOException {
        short len = readShort();
        if (len < 0) {
            throw new IOException("byte-array length negative: " + len);
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    /**
     * Write a byte array framed as {@code [Short length][length bytes]}.
     *
     * <p>Source: {@code Packet.cpp::writeBytes} — {@code writeShort(bytes.length); write(bytes);}.
     */
    public void writeByteArray(byte[] bytes) throws IOException {
        if (bytes.length > Short.MAX_VALUE) {
            throw new IOException("byte-array length " + bytes.length + " exceeds Short.MAX_VALUE");
        }
        writeShort(bytes.length);
        buf.put(bytes);
    }

    // -------------------------------------------------------------- raw byte ops

    /** Copy {@code len} raw bytes from the buffer into {@code dst} starting at {@code offset}. */
    public void readBytes(byte[] dst, int offset, int len) {
        buf.get(dst, offset, len);
    }

    /** Append raw bytes with no framing. */
    public void writeBytes(byte[] src) {
        buf.put(src);
    }

    // -------------------------------------------------------------- mark / reset / toByteArray

    /** Position in the underlying buffer. Useful for the decode loop's rewind-on-underflow logic. */
    public int position() { return buf.position(); }

    /** Drain the written bytes as a byte array (write mode → byte[]). Leaves position unchanged. */
    public byte[] toByteArray() {
        int end = buf.position();
        byte[] out = new byte[end];
        for (int i = 0; i < end; i++) out[i] = buf.get(i);
        return out;
    }
}

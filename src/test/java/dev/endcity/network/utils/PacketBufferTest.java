package dev.endcity.network.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Primitive-level round-trip and wire-format tests for {@link PacketBuffer}. Each test writes a
 * known value with {@code writeXxx}, reads it back with {@code readXxx}, and also verifies the
 * exact byte layout against what {@code DataOutputStream} would produce — that's the ground-truth
 * for big-endian I/O on the JVM.
 */
final class PacketBufferTest {

    private static PacketBuffer read(byte[] bytes) {
        return PacketBuffer.wrap(ByteBuffer.wrap(bytes));
    }

    @Test
    void writeShort_producesBigEndianBytes() {
        PacketBuffer w = PacketBuffer.allocate(2);
        w.writeShort(0x1234);
        assertArrayEquals(new byte[] { 0x12, 0x34 }, w.toByteArray());
    }

    @Test
    void writeInt_producesBigEndianBytes() {
        PacketBuffer w = PacketBuffer.allocate(4);
        w.writeInt(0x11223344);
        assertArrayEquals(new byte[] { 0x11, 0x22, 0x33, 0x44 }, w.toByteArray());
    }

    @Test
    void writeLong_producesBigEndianBytes() {
        PacketBuffer w = PacketBuffer.allocate(8);
        w.writeLong(0x0102030405060708L);
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, w.toByteArray());
    }

    @Test
    void writePlayerUid_isEightBigEndianBytes() {
        // On Win64, PlayerUID is wire-identical to a Long. Confirm.
        PacketBuffer w = PacketBuffer.allocate(8);
        w.writePlayerUid(0xAABBCCDDEEFF0011L);
        assertArrayEquals(
            new byte[] { (byte)0xAA, (byte)0xBB, (byte)0xCC, (byte)0xDD,
                         (byte)0xEE, (byte)0xFF, 0x00, 0x11 },
            w.toByteArray());
    }

    @Test
    void primitiveRoundTrip() throws IOException {
        PacketBuffer w = PacketBuffer.allocate(64);
        w.writeByte(0x7F);
        w.writeBoolean(true);
        w.writeBoolean(false);
        w.writeShort(-1);
        w.writeInt(Integer.MIN_VALUE);
        w.writeLong(Long.MAX_VALUE);
        w.writeFloat(3.5f);
        w.writeDouble(-1.25);
        w.writePlayerUid(42L);

        PacketBuffer r = read(w.toByteArray());
        assertEquals((byte) 0x7F, r.readByte());
        assertTrue(r.readBoolean());
        assertFalse(r.readBoolean());
        assertEquals((short) -1, r.readShort());
        assertEquals(Integer.MIN_VALUE, r.readInt());
        assertEquals(Long.MAX_VALUE, r.readLong());
        assertEquals(3.5f, r.readFloat());
        assertEquals(-1.25, r.readDouble());
        assertEquals(42L, r.readPlayerUid());
    }

    // ---------------------------------------------------------------- LCE UTF

    @Test
    void writeLceUtf_emptyString_isTwoZeroBytes() throws IOException {
        PacketBuffer w = PacketBuffer.allocate(8);
        w.writeLceUtf("");
        assertArrayEquals(new byte[] { 0, 0 }, w.toByteArray());
    }

    @Test
    void writeLceUtf_emitsShortLengthPlusUtf16beChars() throws IOException {
        PacketBuffer w = PacketBuffer.allocate(32);
        w.writeLceUtf("ABC"); // U+0041 U+0042 U+0043
        assertArrayEquals(
            new byte[] { 0, 3,  0, 'A',  0, 'B',  0, 'C' },
            w.toByteArray());
    }

    @Test
    void writeLceUtf_handlesSupplementaryBmpChars() throws IOException {
        // Source is wchar_t-per-char, UTF-16BE. 2-byte BMP chars round-trip; >U+FFFF would surrogate-
        // pair and are out of scope — LCE usernames/level types are ASCII or Latin-1 in practice.
        PacketBuffer w = PacketBuffer.allocate(32);
        w.writeLceUtf("\u00C5"); // Å, U+00C5
        assertArrayEquals(new byte[] { 0, 1,  0x00, (byte) 0xC5 }, w.toByteArray());
    }

    @Test
    void readLceUtf_roundTripsAsciiString() throws IOException {
        PacketBuffer w = PacketBuffer.allocate(32);
        w.writeLceUtf("Dan");
        PacketBuffer r = read(w.toByteArray());
        assertEquals("Dan", r.readLceUtf(32));
    }

    @Test
    void readLceUtf_throwsOnNegativeLength() {
        // Craft bytes: length -1, no body.
        PacketBuffer r = read(new byte[] { (byte) 0xFF, (byte) 0xFF });
        assertThrows(IOException.class, () -> r.readLceUtf(32));
    }

    @Test
    void readLceUtf_throwsWhenOverMax() {
        // length = 10, but maxChars = 5
        PacketBuffer r = read(new byte[] {
            0, 10,
            0, 'a', 0, 'b', 0, 'c', 0, 'd', 0, 'e',
            0, 'f', 0, 'g', 0, 'h', 0, 'i', 0, 'j',
        });
        assertThrows(IOException.class, () -> r.readLceUtf(5));
    }

    // ---------------------------------------------------------------- byte array (Packet::readBytes/writeBytes)

    @Test
    void writeByteArray_emitsShortLengthPrefixedPayload() throws IOException {
        PacketBuffer w = PacketBuffer.allocate(16);
        w.writeByteArray(new byte[] { 1, 2, 3 });
        assertArrayEquals(new byte[] { 0, 3, 1, 2, 3 }, w.toByteArray());
    }

    @Test
    void readByteArray_roundTrips() throws IOException {
        PacketBuffer w = PacketBuffer.allocate(16);
        byte[] payload = { 10, 20, 30, 40 };
        w.writeByteArray(payload);
        PacketBuffer r = read(w.toByteArray());
        assertArrayEquals(payload, r.readByteArray());
    }

    @Test
    void readByteArray_throwsOnNegativeLength() {
        PacketBuffer r = read(new byte[] { (byte) 0xFF, (byte) 0xFE });
        assertThrows(IOException.class, r::readByteArray);
    }
}

package dev.endcity.world.entity;

import dev.endcity.network.utils.PacketBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Byte-for-byte wire-format tests for {@link SynchedEntityData}. The expected bytes are computed
 * by hand from the source's rules in {@code SynchedEntityData.cpp::writeDataItem} + {@code packAll},
 * not from a running reference — the whole point is to pin what "correct" is before we wire this
 * into {@code AddPlayerPacket}.
 *
 * <p>Header byte is always {@code ((type << 5) | (id & 0x1F)) & 0xFF}, values follow in type-natural
 * big-endian order, and a single {@code 0x7F} terminates the stream.
 */
final class SynchedEntityDataTest {

    private static byte[] pack(SynchedEntityData sed) throws IOException {
        PacketBuffer buf = PacketBuffer.allocate(256);
        sed.packAll(buf);
        return buf.toByteArray();
    }

    @Test
    void emptySed_packsToSingleEofByte() throws IOException {
        // An empty SynchedEntityData writes just the 0x7F terminator. This is a legal wire value
        // that the source's `unpack` handles cleanly: first readByte returns EOF_MARKER, loop never
        // enters, returns null-vector.
        assertArrayEquals(new byte[] { 0x7F }, pack(new SynchedEntityData()));
        assertEquals(0, new SynchedEntityData().size());
    }

    @Test
    void byteItem_id0_value0() throws IOException {
        // Minimum M2 player flags: id=0, type=TYPE_BYTE(0), value=0.
        // header = (0 << 5) | 0 = 0x00
        // body   = 0x00
        // eof    = 0x7F
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineByte(0, (byte) 0);
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x7F }, pack(sed));
    }

    @Test
    void floatItem_id6_value20f_isMinimumPlayerHealth() throws IOException {
        // Minimum M2 player health: id=6, type=TYPE_FLOAT(3), value=20.0f.
        // header = (3 << 5) | 6 = 0x66
        // body   = IEEE-754 BE of 20.0f = 0x41 0xA0 0x00 0x00
        // eof    = 0x7F
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineFloat(6, 20.0f);
        assertArrayEquals(
            new byte[] { 0x66, 0x41, (byte) 0xA0, 0x00, 0x00, 0x7F },
            pack(sed));
    }

    @Test
    void multipleItems_areWrittenInAscendingIdOrder() throws IOException {
        // Source's packAll iterates itemsById[0..MAX_ID_VALUE] in index order, so items always
        // come out in ascending-id order regardless of insertion order.
        // Insert id=6 first, then id=0, and verify id=0 is written first on the wire.
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineFloat(6, 20.0f);
        sed.defineByte(0, (byte) 0);
        assertArrayEquals(
            new byte[] {
                0x00, 0x00,                                        // byte id=0, value=0
                0x66, 0x41, (byte) 0xA0, 0x00, 0x00,               // float id=6, value=20.0f
                0x7F                                               // eof
            },
            pack(sed));
        assertEquals(2, sed.size());
    }

    @Test
    void shortItem_headerAndBigEndianBody() throws IOException {
        // id=1, type=TYPE_SHORT(1), value=0x012C (300).
        // header = (1 << 5) | 1 = 0x21
        // body   = 0x01 0x2C
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineShort(1, (short) 0x012C);
        assertArrayEquals(new byte[] { 0x21, 0x01, 0x2C, 0x7F }, pack(sed));
    }

    @Test
    void intItem_headerAndBigEndianBody() throws IOException {
        // id=8, type=TYPE_INT(2), value=0xDEADBEEF.
        // header = (2 << 5) | 8 = 0x48
        // body   = 0xDE 0xAD 0xBE 0xEF
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineInt(8, 0xDEADBEEF);
        assertArrayEquals(
            new byte[] { 0x48, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x7F },
            pack(sed));
    }

    @Test
    void stringItem_usesLceUtfWithShortPrefix() throws IOException {
        // id=5, type=TYPE_STRING(4), value="Hi".
        // header = (4 << 5) | 5 = 0x85
        // body   = LCE UTF = [0x00 0x02][0x00 'H'][0x00 'i']
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineString(5, "Hi");
        assertArrayEquals(
            new byte[] {
                (byte) 0x85,
                0x00, 0x02,                     // LCE UTF char count
                0x00, 'H', 0x00, 'i',           // UTF-16BE chars
                0x7F
            },
            pack(sed));
    }

    @Test
    void stringItem_emptyString_writesZeroLength() throws IOException {
        // header = (4 << 5) | 5 = 0x85, body = [0x00 0x00], then EOF.
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineString(5, "");
        assertArrayEquals(new byte[] { (byte) 0x85, 0x00, 0x00, 0x7F }, pack(sed));
    }

    @Test
    void playerMinimumLoadout_byteAt0_floatAt6() throws IOException {
        // This is the exact byte sequence AddPlayerPacket will embed for a freshly-joined M2
        // player: flags=0 at id=0, health=20.0f at id=6. Written in ascending-id order.
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineByte(0, (byte) 0);
        sed.defineFloat(6, 20.0f);
        assertArrayEquals(
            new byte[] {
                0x00, 0x00,                                        // byte id=0 flags=0
                0x66, 0x41, (byte) 0xA0, 0x00, 0x00,               // float id=6 health=20.0f
                0x7F                                               // eof
            },
            pack(sed));
    }

    @Test
    void id31_rejectedOnAnyDefine_becauseItCollidesWithEof() {
        // id=31 with type=3 (float) produces header 0x7F which the unpacker reads as EOF and
        // desyncs the rest of the stream. Rather than get clever about allowed type/id combos, we
        // reject the whole slot on every define variant. Vanilla only uses ids 0..10 anyway.
        SynchedEntityData sed = new SynchedEntityData();
        assertThrows(IllegalArgumentException.class, () -> sed.defineByte(31, (byte) 0));
        assertThrows(IllegalArgumentException.class, () -> sed.defineShort(31, (short) 0));
        assertThrows(IllegalArgumentException.class, () -> sed.defineInt(31, 0));
        assertThrows(IllegalArgumentException.class, () -> sed.defineFloat(31, 0f));
        assertThrows(IllegalArgumentException.class, () -> sed.defineString(31, ""));
    }

    @Test
    void negativeId_rejected() {
        SynchedEntityData sed = new SynchedEntityData();
        assertThrows(IllegalArgumentException.class, () -> sed.defineByte(-1, (byte) 0));
    }

    @Test
    void stringOverMaxLength_rejected() {
        // Client reads at most MAX_STRING_DATA_LENGTH chars (64) per SynchedEntityData.h:59. A
        // longer string would mean we emit bytes the client ignores, leading to a desync on the
        // next item. Reject loudly at define time.
        SynchedEntityData sed = new SynchedEntityData();
        String sixtyFive = "a".repeat(SynchedEntityData.MAX_STRING_DATA_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> sed.defineString(5, sixtyFive));
    }

    @Test
    void stringAtExactlyMaxLength_accepted() {
        SynchedEntityData sed = new SynchedEntityData();
        String sixtyFour = "a".repeat(SynchedEntityData.MAX_STRING_DATA_LENGTH);
        sed.defineString(5, sixtyFour); // must not throw
        assertEquals(1, sed.size());
    }

    @Test
    void redefinedId_overwritesPreviousValue() throws IOException {
        // The source stores by id, so re-defining an id replaces the previous value. We keep the
        // same behaviour (simpler than erroring, and matches what constructor chains do).
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineByte(0, (byte) 5);
        sed.defineByte(0, (byte) 9);
        assertArrayEquals(new byte[] { 0x00, 0x09, 0x7F }, pack(sed));
        assertEquals(1, sed.size());
    }
}

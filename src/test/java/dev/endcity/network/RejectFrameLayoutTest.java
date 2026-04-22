package dev.endcity.network;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the §3.2 reject-frame byte layout against the C++ source at
 * {@code WinsockNetLayer.cpp:734-742}. The frame is:
 * {@code [0xFF sentinel][0xFF DisconnectPacket id][4-byte big-endian eDisconnectReason]}.
 */
final class RejectFrameLayoutTest {

    @Test
    void rejectFrame_matchesCppSourceLayout_forServerFull() {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put(NetworkConstants.WIN64_SMALLID_REJECT);
        buf.put((byte) NetworkConstants.DISCONNECT_PACKET_ID);
        buf.putInt(NetworkConstants.DisconnectReason.SERVER_FULL);
        buf.flip();

        assertEquals(ByteOrder.BIG_ENDIAN, buf.order(), "ByteBuffer default must be big-endian");
        assertEquals(6, buf.remaining(), "reject frame must be exactly 6 bytes");

        // Byte-for-byte: WinsockNetLayer.cpp:735-741.
        assertEquals((byte) 0xFF, buf.get(0), "byte 0: WIN64_SMALLID_REJECT");
        assertEquals((byte) 0xFF, buf.get(1), "byte 1: DisconnectPacket id 255");
        assertEquals((byte) 0x00, buf.get(2), "byte 2: reason >> 24");
        assertEquals((byte) 0x00, buf.get(3), "byte 3: reason >> 16");
        assertEquals((byte) 0x00, buf.get(4), "byte 4: reason >> 8");
        assertEquals((byte) 0x0C, buf.get(5), "byte 5: reason & 0xFF (eDisconnect_ServerFull = 12)");
    }

    @Test
    void serverFullOrdinal_matchesCppEnumPosition() {
        // Pinning DisconnectPacket.h eDisconnectReason enum:
        // None=0, Quitting=1, Closed=2, LoginTooLong=3, IllegalStance=4, IllegalPosition=5,
        // MovedTooQuickly=6, NoFlying=7, Kicked=8, TimeOut=9, Overflow=10, EndOfStream=11,
        // ServerFull=12, OutdatedServer=13, OutdatedClient=14, UnexpectedPacket=15.
        assertEquals(12, NetworkConstants.DisconnectReason.SERVER_FULL);
        assertEquals(9, NetworkConstants.DisconnectReason.TIME_OUT);
        assertEquals(15, NetworkConstants.DisconnectReason.UNEXPECTED_PACKET);
    }
}

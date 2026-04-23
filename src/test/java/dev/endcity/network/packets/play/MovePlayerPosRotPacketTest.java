package dev.endcity.network.packets.play;

import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MovePlayerPosRotPacketTest {

    private static final PacketListener NOOP_LISTENER = new PacketListener() {};

    @Test
    void id_is13() {
        assertEquals(13, new MovePlayerPosRotPacket().getId());
    }

    @Test
    void roundTrip_exactWireLayout() throws IOException {
        MovePlayerPosRotPacket out = new MovePlayerPosRotPacket(
                8.5, 6.62001, 5.00001, 8.5,
                90.0f, 45.0f,
                true, false);

        PacketBuffer w = PacketBuffer.allocate(64);
        out.write(w);
        byte[] wire = w.toByteArray();

        assertEquals(41, wire.length);
        assertArrayEquals(ByteBuffer.allocate(8).putDouble(8.5).array(), slice(wire, 0, 8));
        assertArrayEquals(ByteBuffer.allocate(8).putDouble(6.62001).array(), slice(wire, 8, 8));
        assertArrayEquals(ByteBuffer.allocate(8).putDouble(5.00001).array(), slice(wire, 16, 8));
        assertArrayEquals(ByteBuffer.allocate(8).putDouble(8.5).array(), slice(wire, 24, 8));
        assertArrayEquals(ByteBuffer.allocate(4).putFloat(90.0f).array(), slice(wire, 32, 4));
        assertArrayEquals(ByteBuffer.allocate(4).putFloat(45.0f).array(), slice(wire, 36, 4));
        assertEquals(0x01, wire[40] & 0xFF);

        MovePlayerPosRotPacket back = new MovePlayerPosRotPacket();
        back.read(PacketBuffer.wrap(ByteBuffer.wrap(wire)));
        assertEquals(8.5, back.x);
        assertEquals(6.62001, back.y);
        assertEquals(5.00001, back.yView);
        assertEquals(8.5, back.z);
        assertEquals(90.0f, back.yRot);
        assertEquals(45.0f, back.xRot);
        assertTrue(back.onGround);
        assertFalse(back.isFlying);
    }

    @Test
    void handleThrows() {
        assertThrows(IOException.class,
                () -> new MovePlayerPosRotPacket().handle(NOOP_LISTENER));
    }

    private static byte[] slice(byte[] src, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, offset, out, 0, len);
        return out;
    }
}

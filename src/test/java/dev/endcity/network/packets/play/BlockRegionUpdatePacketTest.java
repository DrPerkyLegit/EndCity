package dev.endcity.network.packets.play;

import dev.endcity.network.compression.RleZlibCompressor;
import dev.endcity.network.utils.PacketBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BlockRegionUpdatePacketTest {

    @Test
    void id_is51() {
        assertEquals(51, new BlockRegionUpdatePacket().getId());
    }

    @Test
    void write_roundTripsHeaderAndCompressedPayload() throws IOException {
        byte[] raw = new byte[] { 7, 7, 7, 7, 3, 2 };
        byte[] compressed = RleZlibCompressor.compress(raw);
        BlockRegionUpdatePacket out = new BlockRegionUpdatePacket(
                -16, 0, 32,
                16, 256, 16,
                0, true,
                raw);

        PacketBuffer w = PacketBuffer.allocate(128);
        out.write(w);
        byte[] wire = w.toByteArray();

        assertEquals(18 + compressed.length, wire.length);
        assertEquals(BlockRegionUpdatePacket.FLAG_FULL_CHUNK, wire[0] & 0xFF);
        assertEquals(-16, ByteBuffer.wrap(wire, 1, 4).getInt());
        assertEquals(0, ByteBuffer.wrap(wire, 5, 2).getShort());
        assertEquals(32, ByteBuffer.wrap(wire, 7, 4).getInt());
        assertEquals(15, wire[11] & 0xFF);
        assertEquals(255, wire[12] & 0xFF);
        assertEquals(15, wire[13] & 0xFF);
        assertEquals(compressed.length, ByteBuffer.wrap(wire, 14, 4).getInt());

        byte[] payload = new byte[compressed.length];
        System.arraycopy(wire, 18, payload, 0, payload.length);
        assertArrayEquals(compressed, payload);

        BlockRegionUpdatePacket back = new BlockRegionUpdatePacket();
        back.read(PacketBuffer.wrap(ByteBuffer.wrap(wire)));
        assertEquals(BlockRegionUpdatePacket.FLAG_FULL_CHUNK, back.chunkFlags & 0xFF);
        assertEquals(-16, back.x);
        assertEquals(0, back.y);
        assertEquals(32, back.z);
        assertEquals(16, back.xs);
        assertEquals(256, back.ys);
        assertEquals(16, back.zs);
        assertEquals(0, back.levelIdx);
        assertArrayEquals(compressed, back.compressed);
    }

    @Test
    void invalidDimensionsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new BlockRegionUpdatePacket(0, 0, 0, 0, 1, 1, 0, true, new byte[1]));
        assertThrows(IllegalArgumentException.class, () ->
                new BlockRegionUpdatePacket(0, 0, 0, 1, 257, 1, 0, true, new byte[1]));
    }
}

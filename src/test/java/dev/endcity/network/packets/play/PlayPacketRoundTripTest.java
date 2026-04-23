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

/**
 * Byte-for-byte round-trip tests for each M2.2 outbound packet. Same pattern as
 * {@code HandshakePacketRoundTripTest}: populate fields, serialize, assert exact wire bytes,
 * deserialize into a fresh instance, assert equality.
 *
 * <p>Wire formats are all pinned against the source C++ files (e.g.
 * {@code SOURCECODE/Minecraft.World/SetTimePacket.cpp}) and cross-checked against the working
 * reference server at {@code .MinecraftLegacyEdition/server/src/core/PacketHandler.cpp}.
 */
final class PlayPacketRoundTripTest {

    private static PacketBuffer readFrom(byte[] bytes) {
        return PacketBuffer.wrap(ByteBuffer.wrap(bytes));
    }

    /** No-op listener used to verify server-only packets throw on handle(). */
    private static final PacketListener NOOP_LISTENER = new PacketListener() {};

    // ---------------------------------------------------------------- SetTimePacket (id=4)

    @Test
    void setTimePacket_id_is_4() {
        assertEquals(4, new SetTimePacket().getId());
    }

    @Test
    void setTimePacket_roundTrip_exactWireLayout() throws IOException {
        SetTimePacket out = new SetTimePacket(0x0102030405060708L, 0x1122334455667788L);

        PacketBuffer w = PacketBuffer.allocate(16);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Source wire: [Long gameTime][Long dayTime], both BE. 16 bytes body.
        assertEquals(16, wire.length);
        assertArrayEquals(new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88
        }, wire);

        SetTimePacket back = new SetTimePacket();
        back.read(readFrom(wire));
        assertEquals(0x0102030405060708L, back.gameTime);
        assertEquals(0x1122334455667788L, back.dayTime);
    }

    @Test
    void setTimePacket_handleThrows() {
        // Server-only packet — never dispatched inbound.
        assertThrows(UnsupportedOperationException.class,
                () -> new SetTimePacket().handle(NOOP_LISTENER));
    }

    // ---------------------------------------------------------------- SetSpawnPositionPacket (id=6)

    @Test
    void setSpawnPositionPacket_id_is_6() {
        assertEquals(6, new SetSpawnPositionPacket().getId());
    }

    @Test
    void setSpawnPositionPacket_roundTrip_exactWireLayout() throws IOException {
        SetSpawnPositionPacket out = new SetSpawnPositionPacket(0x01020304, 64, -0x07080910);

        PacketBuffer w = PacketBuffer.allocate(12);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Source wire: [Int x][Int y][Int z], all BE. 12 bytes body.
        assertEquals(12, wire.length);
        assertArrayEquals(new byte[] {
                0x01, 0x02, 0x03, 0x04,                     // x = 0x01020304
                0x00, 0x00, 0x00, 0x40,                     // y = 64
                (byte) 0xF8, (byte) 0xF7, (byte) 0xF6, (byte) 0xF0  // z = -0x07080910
        }, wire);

        SetSpawnPositionPacket back = new SetSpawnPositionPacket();
        back.read(readFrom(wire));
        assertEquals(0x01020304, back.x);
        assertEquals(64, back.y);
        assertEquals(-0x07080910, back.z);
    }

    @Test
    void setSpawnPositionPacket_handleThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> new SetSpawnPositionPacket().handle(NOOP_LISTENER));
    }

    // ---------------------------------------------------------------- SetHealthPacket (id=8)

    @Test
    void setHealthPacket_id_is_8() {
        assertEquals(8, new SetHealthPacket().getId());
    }

    @Test
    void setHealthPacket_roundTrip_fullHealth() throws IOException {
        // Typical full-health full-food player.
        SetHealthPacket out = new SetHealthPacket(20.0f, (short) 20, 5.0f, SetHealthPacket.DAMAGE_SOURCE_UNKNOWN);

        PacketBuffer w = PacketBuffer.allocate(11);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Source wire: [Float health][Short food][Float saturation][Byte damageSource]. 11 bytes body.
        // IEEE 754 single for 20.0f = 0x41A00000. For 5.0f = 0x40A00000.
        assertEquals(11, wire.length);
        assertArrayEquals(new byte[] {
                0x41, (byte) 0xA0, 0x00, 0x00,  // health = 20.0f
                0x00, 0x14,                      // food = 20
                0x40, (byte) 0xA0, 0x00, 0x00,  // saturation = 5.0f
                0x00                             // damageSource = 0
        }, wire);

        SetHealthPacket back = new SetHealthPacket();
        back.read(readFrom(wire));
        assertEquals(20.0f, back.health);
        assertEquals((short) 20, back.food);
        assertEquals(5.0f, back.saturation);
        assertEquals(SetHealthPacket.DAMAGE_SOURCE_UNKNOWN, back.damageSource);
    }

    @Test
    void setHealthPacket_food_isShortNotByte() throws IOException {
        // Regression guard: vanilla MC uses byte-sized food but LCE wire uses short. PACKETS.md
        // also says short; if someone "optimises" this to byte, everything downstream desyncs.
        SetHealthPacket out = new SetHealthPacket(20.0f, (short) 0x0123, 0f, (byte) 0);

        PacketBuffer w = PacketBuffer.allocate(11);
        out.write(w);
        byte[] wire = w.toByteArray();

        // food bytes 4-5 should be 0x01 0x23 (BE short), not just 0x23 (byte).
        assertEquals(0x01, wire[4] & 0xFF);
        assertEquals(0x23, wire[5] & 0xFF);
    }

    @Test
    void setHealthPacket_handleThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> new SetHealthPacket().handle(NOOP_LISTENER));
    }

    // ---------------------------------------------------------------- PlayerAbilitiesPacket (id=202)

    @Test
    void playerAbilitiesPacket_id_is_202() {
        assertEquals(202, new PlayerAbilitiesPacket().getId());
    }

    @Test
    void playerAbilitiesPacket_flagBits_matchSource() {
        // Source PlayerAbilitiesPacket.h: 1<<0, 1<<1, 1<<2, 1<<3
        assertEquals(0x01, PlayerAbilitiesPacket.FLAG_INVULNERABLE);
        assertEquals(0x02, PlayerAbilitiesPacket.FLAG_FLYING);
        assertEquals(0x04, PlayerAbilitiesPacket.FLAG_CAN_FLY);
        assertEquals(0x08, PlayerAbilitiesPacket.FLAG_INSTABUILD);
    }

    @Test
    void playerAbilitiesPacket_roundTrip_creativeLoadout() throws IOException {
        // Creative-mode player: canFly + instabuild (but not flying, not invulnerable).
        PlayerAbilitiesPacket out = new PlayerAbilitiesPacket();
        out.setCanFly(true);
        out.setInstabuild(true);
        out.flyingSpeed = PlayerAbilitiesPacket.DEFAULT_FLYING_SPEED;  // 0.05f
        out.walkingSpeed = PlayerAbilitiesPacket.DEFAULT_WALKING_SPEED; // 0.1f

        // Expected flags byte: CAN_FLY | INSTABUILD = 0x04 | 0x08 = 0x0C
        assertEquals(0x0C, out.flags);
        assertFalse(out.isInvulnerable());
        assertFalse(out.isFlying());
        assertTrue(out.canFly());
        assertTrue(out.canInstabuild());

        PacketBuffer w = PacketBuffer.allocate(9);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Source wire: [Byte flags][Float flyingSpeed][Float walkingSpeed]. 9 bytes body.
        // 0.05f = 0x3D4CCCCD, 0.1f = 0x3DCCCCCD
        assertEquals(9, wire.length);
        assertArrayEquals(new byte[] {
                0x0C,
                0x3D, 0x4C, (byte) 0xCC, (byte) 0xCD,  // 0.05f
                0x3D, (byte) 0xCC, (byte) 0xCC, (byte) 0xCD   // 0.1f
        }, wire);

        PlayerAbilitiesPacket back = new PlayerAbilitiesPacket();
        back.read(readFrom(wire));
        assertEquals(0x0C, back.flags);
        assertEquals(0.05f, back.flyingSpeed);
        assertEquals(0.1f, back.walkingSpeed);
    }

    @Test
    void playerAbilitiesPacket_flagSetters_areIndependent() {
        PlayerAbilitiesPacket p = new PlayerAbilitiesPacket();
        p.setInvulnerable(true);
        p.setFlying(true);
        p.setCanFly(true);
        p.setInstabuild(true);
        assertEquals(0x0F, p.flags);

        p.setFlying(false);
        assertEquals(0x0D, p.flags);  // 0x0F with 0x02 cleared
        assertTrue(p.isInvulnerable());
        assertFalse(p.isFlying());
        assertTrue(p.canFly());
        assertTrue(p.canInstabuild());
    }

    @Test
    void playerAbilitiesPacket_handleThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> new PlayerAbilitiesPacket().handle(NOOP_LISTENER));
    }

    // ---------------------------------------------------------------- PlayerCommandPacket (id=19)

    @Test
    void playerCommandPacket_id_is_19() {
        assertEquals(19, new PlayerCommandPacket().getId());
    }

    @Test
    void playerCommandPacket_roundTrip_exactWireLayout() throws IOException {
        PlayerCommandPacket out = new PlayerCommandPacket(0x01020304, PlayerCommandPacket.START_SPRINTING, 0x11223344);

        PacketBuffer w = PacketBuffer.allocate(9);
        out.write(w);
        byte[] wire = w.toByteArray();

        assertEquals(9, wire.length);
        assertArrayEquals(new byte[] {
                0x01, 0x02, 0x03, 0x04,
                0x04,
                0x11, 0x22, 0x33, 0x44
        }, wire);

        PlayerCommandPacket back = new PlayerCommandPacket();
        back.read(readFrom(wire));
        assertEquals(0x01020304, back.entityId);
        assertEquals(PlayerCommandPacket.START_SPRINTING, back.action);
        assertEquals(0x11223344, back.data);
    }

    // ---------------------------------------------------------------- ChunkVisibilityPacket (id=50)

    @Test
    void chunkVisibilityPacket_id_is_50() {
        assertEquals(50, new ChunkVisibilityPacket().getId());
    }

    @Test
    void chunkVisibilityPacket_roundTrip_visible() throws IOException {
        ChunkVisibilityPacket out = new ChunkVisibilityPacket(5, -3, true);

        PacketBuffer w = PacketBuffer.allocate(9);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Source wire: [Int x][Int z][Byte visible]. 9 bytes body. visible=true → 0x01.
        assertEquals(9, wire.length);
        assertArrayEquals(new byte[] {
                0x00, 0x00, 0x00, 0x05,                              // x = 5
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFD,  // z = -3
                0x01                                                  // visible = true
        }, wire);

        ChunkVisibilityPacket back = new ChunkVisibilityPacket();
        back.read(readFrom(wire));
        assertEquals(5, back.x);
        assertEquals(-3, back.z);
        assertTrue(back.visible);
    }

    @Test
    void chunkVisibilityPacket_roundTrip_invisible() throws IOException {
        ChunkVisibilityPacket out = new ChunkVisibilityPacket(0, 0, false);

        PacketBuffer w = PacketBuffer.allocate(9);
        out.write(w);
        byte[] wire = w.toByteArray();

        assertEquals(0x00, wire[8] & 0xFF);  // visible=false → 0x00

        ChunkVisibilityPacket back = new ChunkVisibilityPacket();
        back.read(readFrom(wire));
        assertFalse(back.visible);
    }

    @Test
    void chunkVisibilityPacket_handleThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> new ChunkVisibilityPacket().handle(NOOP_LISTENER));
    }

    // ---------------------------------------------------------------- ChunkVisibilityAreaPacket (id=155)

    @Test
    void chunkVisibilityAreaPacket_id_is_155() {
        assertEquals(155, new ChunkVisibilityAreaPacket().getId());
    }

    @Test
    void chunkVisibilityAreaPacket_roundTrip_exactWireLayout() throws IOException {
        // Field order is minX, maxX, minZ, maxZ (x-pair then z-pair, NOT min-first by axis).
        ChunkVisibilityAreaPacket out = new ChunkVisibilityAreaPacket(-8, 8, -4, 4);

        PacketBuffer w = PacketBuffer.allocate(16);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Source wire: [Int minX][Int maxX][Int minZ][Int maxZ]. 16 bytes body.
        assertEquals(16, wire.length);
        assertArrayEquals(new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xF8,  // minX = -8
                0x00, 0x00, 0x00, 0x08,                              // maxX = 8
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFC,  // minZ = -4
                0x00, 0x00, 0x00, 0x04                               // maxZ = 4
        }, wire);

        ChunkVisibilityAreaPacket back = new ChunkVisibilityAreaPacket();
        back.read(readFrom(wire));
        assertEquals(-8, back.minX);
        assertEquals(8,  back.maxX);
        assertEquals(-4, back.minZ);
        assertEquals(4,  back.maxZ);
    }

    @Test
    void chunkVisibilityAreaPacket_fieldOrder_isXPairThenZPair() throws IOException {
        // Regression guard: a plausible-but-wrong ordering is minX, minZ, maxX, maxZ. Use values
        // where those two orderings produce visibly different bytes at known offsets.
        ChunkVisibilityAreaPacket out = new ChunkVisibilityAreaPacket(1, 2, 3, 4);

        PacketBuffer w = PacketBuffer.allocate(16);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Bytes 4-7 must be maxX=2, not minZ=3.
        assertEquals(2, ((wire[4] & 0xFF) << 24) | ((wire[5] & 0xFF) << 16) | ((wire[6] & 0xFF) << 8) | (wire[7] & 0xFF));
        // Bytes 8-11 must be minZ=3, not maxX=2.
        assertEquals(3, ((wire[8] & 0xFF) << 24) | ((wire[9] & 0xFF) << 16) | ((wire[10] & 0xFF) << 8) | (wire[11] & 0xFF));
    }

    @Test
    void chunkVisibilityAreaPacket_handleThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> new ChunkVisibilityAreaPacket().handle(NOOP_LISTENER));
    }
}

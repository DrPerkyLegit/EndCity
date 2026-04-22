package dev.endcity.network.packets.handshake;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.utils.PacketBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-for-byte round-trip tests for each M1 packet. Pattern:
 * <ol>
 *   <li>Populate packet fields.</li>
 *   <li>Serialize to {@link PacketBuffer}.</li>
 *   <li>Deserialize into a fresh packet instance.</li>
 *   <li>Assert every field matches.</li>
 * </ol>
 * Where the byte layout is particularly load-bearing (4-byte reason in DisconnectPacket; the
 * {@code _LARGE_WORLDS} tail on LoginPacket), we additionally assert exact byte sequences.
 */
final class HandshakePacketRoundTripTest {

    private static PacketBuffer readFrom(byte[] bytes) {
        return PacketBuffer.wrap(ByteBuffer.wrap(bytes));
    }

    // ---------------------------------------------------------------- DisconnectPacket

    @Test
    void disconnectPacket_roundTrip_serverFull() throws IOException {
        DisconnectPacket out = new DisconnectPacket(NetworkConstants.DisconnectReason.SERVER_FULL);
        PacketBuffer w = PacketBuffer.allocate(8);
        out.write(w);
        byte[] wire = w.toByteArray();

        // 4 bytes big-endian = 0x0000000C = 12 = SERVER_FULL
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x0C }, wire);

        DisconnectPacket back = new DisconnectPacket();
        back.read(readFrom(wire));
        assertEquals(NetworkConstants.DisconnectReason.SERVER_FULL, back.reason);
    }

    @Test
    void disconnectPacket_id_is_255() {
        assertEquals(255, new DisconnectPacket().getId());
    }

    // ---------------------------------------------------------------- KeepAlivePacket

    @Test
    void keepAlivePacket_roundTrip() throws IOException {
        int token = 0xDEADBEEF;
        KeepAlivePacket out = new KeepAlivePacket(token);

        PacketBuffer w = PacketBuffer.allocate(4);
        out.write(w);
        byte[] wire = w.toByteArray();
        assertArrayEquals(new byte[] { (byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF }, wire);

        KeepAlivePacket back = new KeepAlivePacket();
        back.read(readFrom(wire));
        assertEquals(token, back.token);
    }

    @Test
    void keepAlivePacket_id_is_0() {
        assertEquals(0, new KeepAlivePacket().getId());
    }

    // ---------------------------------------------------------------- PreLoginPacket

    @Test
    void preLoginPacket_roundTrip_realisticPayload() throws IOException {
        PreLoginPacket out = new PreLoginPacket();
        out.netcodeVersion = (short) NetworkConstants.MINECRAFT_NET_VERSION; // 560
        out.loginKey = "Racoon";
        out.friendsOnlyBits = 0x01;
        out.ugcPlayersVersion = 42;
        out.playerCount = 2;
        out.playerXuids = new long[] { 0x11111111_11111111L, 0x22222222_22222222L };
        // uniqueSaveName: 14 bytes.
        byte[] saveName = "MyWorld\0\0\0\0\0\0\0".getBytes();
        assertEquals(PreLoginPacket.UNIQUE_SAVE_NAME_LEN, saveName.length);
        System.arraycopy(saveName, 0, out.uniqueSaveName, 0, saveName.length);
        out.serverSettings = 0x07;
        out.hostIndex = 0;
        out.texturePackId = 99;

        PacketBuffer w = PacketBuffer.allocate(256);
        out.write(w);
        byte[] wire = w.toByteArray();

        PreLoginPacket back = new PreLoginPacket();
        back.read(readFrom(wire));

        assertEquals(out.netcodeVersion, back.netcodeVersion);
        assertEquals(out.loginKey, back.loginKey);
        assertEquals(out.friendsOnlyBits, back.friendsOnlyBits);
        assertEquals(out.ugcPlayersVersion, back.ugcPlayersVersion);
        assertEquals(out.playerCount, back.playerCount);
        assertArrayEquals(out.playerXuids, back.playerXuids);
        assertArrayEquals(out.uniqueSaveName, back.uniqueSaveName);
        assertEquals(out.serverSettings, back.serverSettings);
        assertEquals(out.hostIndex, back.hostIndex);
        assertEquals(out.texturePackId, back.texturePackId);
    }

    @Test
    void preLoginPacket_id_is_2() {
        assertEquals(2, new PreLoginPacket().getId());
    }

    @Test
    void preLoginPacket_uniqueSaveNameIsUnprefixed14Bytes() throws IOException {
        // Critical: source writes m_szUniqueSaveName as raw bytes, NOT Packet::writeBytes (which
        // has a Short length prefix). This test would fail if someone wired it up wrong.
        PreLoginPacket out = new PreLoginPacket();
        out.netcodeVersion = 560;
        out.loginKey = "";
        out.friendsOnlyBits = 0;
        out.ugcPlayersVersion = 0;
        out.playerCount = 0;
        // save name: 14 bytes of A, B, C, ...
        for (int i = 0; i < 14; i++) out.uniqueSaveName[i] = (byte) ('A' + i);
        out.serverSettings = 0;
        out.hostIndex = 0;
        out.texturePackId = 0;

        PacketBuffer w = PacketBuffer.allocate(64);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Expected structure:
        //   [netcodeVersion:2=0x0230] [loginKey:2=0x0000] [friendsOnlyBits:1=0x00]
        //   [ugcPlayersVersion:4=0]   [playerCount:1=0]
        //   [uniqueSaveName:14=ABCDEFGHIJKLMN]  <-- CRITICAL: no length prefix
        //   [serverSettings:4=0] [hostIndex:1=0] [texturePackId:4=0]
        assertEquals(2 + 2 + 1 + 4 + 1 + 14 + 4 + 1 + 4, wire.length);

        // Bytes 10..23 should be exactly ABCDEFGHIJKLMN.
        byte[] expectedSaveName = { 'A','B','C','D','E','F','G','H','I','J','K','L','M','N' };
        byte[] actualSaveName = new byte[14];
        System.arraycopy(wire, 10, actualSaveName, 0, 14);
        assertArrayEquals(expectedSaveName, actualSaveName);
    }

    // ---------------------------------------------------------------- LoginPacket

    @Test
    void loginPacket_roundTrip_serverToClient() throws IOException {
        // Simulate a fully-populated S→C LoginPacket as the M1 PreLogin handler would produce.
        LoginPacket out = new LoginPacket();
        out.clientVersion = NetworkConstants.NETWORK_PROTOCOL_VERSION;       // 78
        out.userName = "Dan";
        out.levelTypeName = "default";
        out.seed = 0L;
        out.gameType = 0;   // Survival
        out.dimension = 0;  // Overworld
        out.mapHeight = 0;  // (byte)256 per source
        out.maxPlayers = 8;
        out.offlineXuid = NetworkConstants.INVALID_XUID;
        out.onlineXuid = NetworkConstants.INVALID_XUID;
        out.friendsOnlyUGC = false;
        out.ugcPlayersVersion = 0;
        out.difficulty = 1;  // Easy
        out.multiplayerInstanceId = 4; // == small ID
        out.playerIndex = 4;           // == small ID
        out.playerSkinId = 0;
        out.playerCapeId = 0;
        out.isGuest = false;
        out.newSeaLevel = true;
        out.uiGamePrivileges = 0;
        out.xzSize = NetworkConstants.LEVEL_MAX_WIDTH;       // 320
        out.hellScale = NetworkConstants.HELL_LEVEL_MAX_SCALE; // 8

        PacketBuffer w = PacketBuffer.allocate(256);
        out.write(w);
        byte[] wire = w.toByteArray();

        LoginPacket back = new LoginPacket();
        back.read(readFrom(wire));

        assertEquals(78, back.clientVersion);
        assertEquals("Dan", back.userName);
        assertEquals("default", back.levelTypeName);
        assertEquals(0L, back.seed);
        assertEquals(0, back.gameType);
        assertEquals(0, back.dimension);
        assertEquals(0, back.mapHeight);
        assertEquals(8, back.maxPlayers);
        assertEquals(NetworkConstants.INVALID_XUID, back.offlineXuid);
        assertEquals(NetworkConstants.INVALID_XUID, back.onlineXuid);
        assertFalse(back.friendsOnlyUGC);
        assertEquals(0, back.ugcPlayersVersion);
        assertEquals(1, back.difficulty);
        assertEquals(4, back.multiplayerInstanceId);
        assertEquals(4, back.playerIndex);
        assertEquals(0, back.playerSkinId);
        assertEquals(0, back.playerCapeId);
        assertFalse(back.isGuest);
        assertTrue(back.newSeaLevel);
        assertEquals(0, back.uiGamePrivileges);
        assertEquals(320, back.xzSize);
        assertEquals(8, back.hellScale);
    }

    @Test
    void loginPacket_largeWorldsTail_isEmittedAtEnd() throws IOException {
        // _LARGE_WORLDS is always on for Win64. xzSize (Short) + hellScale (Byte) = 3 bytes at end.
        LoginPacket out = new LoginPacket();
        out.clientVersion = 78;
        out.userName = "";
        out.levelTypeName = "";
        out.xzSize = 0x1234;
        out.hellScale = 0x56;

        PacketBuffer w = PacketBuffer.allocate(128);
        out.write(w);
        byte[] wire = w.toByteArray();

        // Last 3 bytes must be [0x12, 0x34, 0x56].
        int len = wire.length;
        assertEquals(0x12, wire[len - 3] & 0xFF);
        assertEquals(0x34, wire[len - 2] & 0xFF);
        assertEquals(0x56, wire[len - 1] & 0xFF);
    }

    @Test
    void loginPacket_id_is_1() {
        assertEquals(1, new LoginPacket().getId());
    }
}

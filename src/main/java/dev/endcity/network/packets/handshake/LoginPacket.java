package dev.endcity.network.packets.handshake;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 1, ⇌. Bidirectional but asymmetric — the same struct carries different subsets of fields
 * depending on direction. Field layout on the wire is identical; only which side populates each
 * field differs. See {@code EndCity_Design.md} §7.2.
 *
 * <p>Source: {@code Minecraft.World/LoginPacket.cpp::read/write}. Field order (cross-referenced
 * against both methods to confirm symmetry):
 *
 * <ol>
 *   <li>{@code clientVersion}             (Int)     — S→C. {@link NetworkConstants#NETWORK_PROTOCOL_VERSION} = 78.</li>
 *   <li>{@code userName}                  (UTF, max {@link NetworkConstants#PLAYER_MAX_NAME_LENGTH} = 20).</li>
 *   <li>{@code levelTypeName}             (UTF, max 16) — S→C. {@code ""} → client uses {@code lvl_normal}.</li>
 *   <li>{@code seed}                      (Long)    — S→C. World seed.</li>
 *   <li>{@code gameType}                  (Int)     — S→C. 0=Survival, 1=Creative, 2=Adventure.</li>
 *   <li>{@code dimension}                 (Byte)    — S→C. 0=Overworld, -1=Nether, 1=End.</li>
 *   <li>{@code mapHeight}                 (Byte)    — S→C. Vestigial; source sends {@code (byte)256 = 0}.</li>
 *   <li>{@code maxPlayers}                (Byte)    — S→C. Configured cap.</li>
 *   <li>{@code offlineXuid}               (PlayerUID = Long) — C→S.</li>
 *   <li>{@code onlineXuid}                (PlayerUID = Long) — C→S.</li>
 *   <li>{@code friendsOnlyUGC}            (Boolean) — C→S.</li>
 *   <li>{@code ugcPlayersVersion}         (Int)     — C→S.</li>
 *   <li>{@code difficulty}                (Byte)    — S→C. 0..3.</li>
 *   <li>{@code multiplayerInstanceId}     (Int)     — S→C. Session id.</li>
 *   <li>{@code playerIndex}               (Byte)    — S→C. = small ID.</li>
 *   <li>{@code playerSkinId}              (Int)     — C→S.</li>
 *   <li>{@code playerCapeId}              (Int)     — C→S.</li>
 *   <li>{@code isGuest}                   (Boolean) — C→S.</li>
 *   <li>{@code newSeaLevel}               (Boolean) — S→C. TU14+ default true.</li>
 *   <li>{@code uiGamePrivileges}          (Int)     — S→C. Bitfield of permitted UI features.</li>
 *   <li>{@code xzSize}                    (Short)   — {@code _LARGE_WORLDS} only. Always present on Win64.</li>
 *   <li>{@code hellScale}                 (Byte)    — {@code _LARGE_WORLDS} only. Always present on Win64.</li>
 * </ol>
 */
public final class LoginPacket extends Packet {

    public int     clientVersion;
    public String  userName = "";
    public String  levelTypeName = "";
    public long    seed;
    public int     gameType;
    public byte    dimension;
    public byte    mapHeight;
    public byte    maxPlayers;
    public long    offlineXuid;
    public long    onlineXuid;
    public boolean friendsOnlyUGC;
    public int     ugcPlayersVersion;
    public byte    difficulty;
    public int     multiplayerInstanceId;
    public byte    playerIndex;
    public int     playerSkinId;
    public int     playerCapeId;
    public boolean isGuest;
    public boolean newSeaLevel;
    public int     uiGamePrivileges;
    public int     xzSize      = NetworkConstants.LEVEL_MAX_WIDTH;
    public int     hellScale   = NetworkConstants.HELL_LEVEL_MAX_SCALE;

    public LoginPacket() {
        // All fields default-zero; offlineXuid/onlineXuid default to INVALID_XUID = 0.
        this.offlineXuid = NetworkConstants.INVALID_XUID;
        this.onlineXuid  = NetworkConstants.INVALID_XUID;
        this.difficulty  = 1;
    }

    @Override
    public int getId() { return 1; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        clientVersion         = buf.readInt();
        userName              = buf.readLceUtf(NetworkConstants.PLAYER_MAX_NAME_LENGTH);
        levelTypeName         = buf.readLceUtf(NetworkConstants.LEVEL_TYPE_NAME_MAX_LEN);
        seed                  = buf.readLong();
        gameType              = buf.readInt();
        dimension             = buf.readByte();
        mapHeight             = buf.readByte();
        maxPlayers            = buf.readByte();
        offlineXuid           = buf.readPlayerUid();
        onlineXuid            = buf.readPlayerUid();
        friendsOnlyUGC        = buf.readBoolean();
        ugcPlayersVersion     = buf.readInt();
        difficulty            = buf.readByte();
        multiplayerInstanceId = buf.readInt();
        playerIndex           = buf.readByte();
        playerSkinId          = buf.readInt();
        playerCapeId          = buf.readInt();
        isGuest               = buf.readBoolean();
        newSeaLevel           = buf.readBoolean();
        uiGamePrivileges      = buf.readInt();
        // _LARGE_WORLDS tail — always present on Win64.
        xzSize                = buf.readShort();
        hellScale             = buf.readByte() & 0xFF;
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(clientVersion);
        buf.writeLceUtf(userName);
        buf.writeLceUtf(levelTypeName);
        buf.writeLong(seed);
        buf.writeInt(gameType);
        buf.writeByte(dimension);
        buf.writeByte(mapHeight);
        buf.writeByte(maxPlayers);
        buf.writePlayerUid(offlineXuid);
        buf.writePlayerUid(onlineXuid);
        buf.writeBoolean(friendsOnlyUGC);
        buf.writeInt(ugcPlayersVersion);
        buf.writeByte(difficulty);
        buf.writeInt(multiplayerInstanceId);
        buf.writeByte(playerIndex);
        buf.writeInt(playerSkinId);
        buf.writeInt(playerCapeId);
        buf.writeBoolean(isGuest);
        buf.writeBoolean(newSeaLevel);
        buf.writeInt(uiGamePrivileges);
        // _LARGE_WORLDS tail.
        buf.writeShort(xzSize);
        buf.writeByte(hellScale);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleLogin(this);
    }
}

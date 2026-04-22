package dev.endcity.network.packets.handshake;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 2, C&rarr;S. The first packet every client sends after the small-ID handshake.
 *
 * <p>Source: {@code Minecraft.World/PreLoginPacket.cpp::read/write}. Field order on the wire (see
 * {@code EndCity_Design.md} &sect;7.1):
 * <ol>
 *   <li>{@code netcodeVersion} (Short) &mdash; must equal {@link NetworkConstants#MINECRAFT_NET_VERSION} = 560.</li>
 *   <li>{@code loginKey} (UTF, max 32 chars) &mdash; the client's chosen username.</li>
 *   <li>{@code friendsOnlyBits} (Byte) &mdash; per-local-user friends-only restrictions.</li>
 *   <li>{@code ugcPlayersVersion} (Int) &mdash; user-generated-content bundle version.</li>
 *   <li>{@code playerCount} (Byte) &mdash; number of XUIDs that follow. Source clamps to
 *       {@code MINECRAFT_NET_MAX_PLAYERS = 256}. Values above that are silently reduced on read.</li>
 *   <li>{@code playerXuids} &times; playerCount (Long each; 8 bytes, big-endian on Win64).</li>
 *   <li>{@code uniqueSaveName} (14 raw bytes, NOT length-prefixed).</li>
 *   <li>{@code serverSettings} (Int) &mdash; bitfield of host options.</li>
 *   <li>{@code hostIndex} (Byte) &mdash; which local user is the primary.</li>
 *   <li>{@code texturePackId} (Int) &mdash; client-side resource pack id.</li>
 * </ol>
 */
public final class PreLoginPacket extends Packet {

    /** Length of {@code uniqueSaveName}, matching {@code PreLoginPacket::m_iSaveNameLen}. */
    public static final int UNIQUE_SAVE_NAME_LEN = 14;

    public short netcodeVersion;
    public String loginKey = "";
    public byte friendsOnlyBits;
    public int ugcPlayersVersion;
    /** Source's {@code m_dwPlayerCount} is {@code DWORD} in memory but Byte on the wire. */
    public int playerCount;
    public long[] playerXuids = new long[0];
    public final byte[] uniqueSaveName = new byte[UNIQUE_SAVE_NAME_LEN];
    public int serverSettings;
    public byte hostIndex;
    public int texturePackId;

    @Override
    public int getId() { return 2; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        netcodeVersion    = buf.readShort();
        loginKey          = buf.readLceUtf(32);
        friendsOnlyBits   = buf.readByte();
        ugcPlayersVersion = buf.readInt();

        // Byte on the wire; source then clamps the in-memory DWORD to MINECRAFT_NET_MAX_PLAYERS.
        // Byte is signed in Java — mask to get the unsigned value on the wire.
        int count = buf.readByte() & 0xFF;
        if (count > NetworkConstants.MINECRAFT_NET_MAX_PLAYERS) {
            count = NetworkConstants.MINECRAFT_NET_MAX_PLAYERS;
        }
        playerCount = count;

        playerXuids = new long[count];
        for (int i = 0; i < count; i++) {
            playerXuids[i] = buf.readPlayerUid();
        }

        buf.readBytes(uniqueSaveName, 0, UNIQUE_SAVE_NAME_LEN);

        serverSettings = buf.readInt();
        hostIndex      = buf.readByte();
        texturePackId  = buf.readInt();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeShort(netcodeVersion);
        buf.writeLceUtf(loginKey);
        buf.writeByte(friendsOnlyBits);
        buf.writeInt(ugcPlayersVersion);
        buf.writeByte(playerCount);  // Byte on wire; writeByte masks to 0xFF
        for (int i = 0; i < playerCount; i++) {
            buf.writePlayerUid(playerXuids[i]);
        }
        buf.writeBytes(uniqueSaveName);
        buf.writeInt(serverSettings);
        buf.writeByte(hostIndex);
        buf.writeInt(texturePackId);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handlePreLogin(this);
    }
}

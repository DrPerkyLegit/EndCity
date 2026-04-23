package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;
import dev.endcity.world.entity.SynchedEntityData;

import java.io.IOException;

/**
 * Packet id 20, server->client player-entity spawn.
 *
 * <p>Source: {@code SOURCECODE/Minecraft.World/AddPlayerPacket.cpp}. Wire body:
 * {@code [Int entityId][utf16 name][Int x][Int y][Int z][Byte yRot][Byte xRot][Byte yHeadRot]
 * [Short carriedItem][PlayerUID offlineXuid][PlayerUID onlineXuid][Byte playerIndex]
 * [Int skinId][Int capeId][Int privileges][SynchedEntityData]}.
 *
 * <p>The coordinate fields are the protocol's fixed-point x32 integers, not doubles. Use
 * {@link #fromWorldState} when constructing from world-space values.
 */
public final class AddPlayerPacket extends Packet {

    // Source-pinned player metadata slots.
    public static final int DATA_SHARED_FLAGS_ID = 0;
    public static final int DATA_AIR_SUPPLY_ID = 1;
    public static final int DATA_HEALTH_ID = 6;
    public static final int DATA_EFFECT_COLOR_ID = 7;
    public static final int DATA_EFFECT_AMBIENCE_ID = 8;
    public static final int DATA_ARROW_COUNT_ID = 9;
    public static final int DATA_PLAYER_FLAGS_ID = 16;
    public static final int DATA_PLAYER_ABSORPTION_ID = 17;
    public static final int DATA_SCORE_ID = 18;

    public int entityId;
    public String name = "";
    public int x;
    public int y;
    public int z;
    public int yRot;
    public int xRot;
    public int yHeadRot;
    public short carriedItem;
    public long offlineXuid;
    public long onlineXuid;
    public int playerIndex;
    public int skinId;
    public int capeId;
    public int privileges;
    public SynchedEntityData entityData;
    public byte[] packedEntityData;

    public AddPlayerPacket() {}

    public static AddPlayerPacket fromWorldState(
            int entityId,
            String name,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            float yHeadRot,
            short carriedItem,
            long offlineXuid,
            long onlineXuid,
            int playerIndex,
            int skinId,
            int capeId,
            int privileges,
            SynchedEntityData entityData) {
        AddPlayerPacket packet = new AddPlayerPacket();
        packet.entityId = entityId;
        packet.name = name;
        packet.x = floorFixed32(x);
        packet.y = floorFixed32(y);
        packet.z = floorFixed32(z);
        packet.yRot = packRotationByte(yRot);
        packet.xRot = packRotationByte(xRot);
        packet.yHeadRot = packRotationByte(yHeadRot);
        packet.carriedItem = carriedItem;
        packet.offlineXuid = offlineXuid;
        packet.onlineXuid = onlineXuid;
        packet.playerIndex = playerIndex & 0xFF;
        packet.skinId = skinId;
        packet.capeId = capeId;
        packet.privileges = privileges;
        packet.entityData = entityData;
        return packet;
    }

    /**
     * Build the source-pinned default metadata layout for a freshly joined player.
     */
    public static SynchedEntityData defaultPlayerEntityData(float health) {
        SynchedEntityData sed = new SynchedEntityData();
        sed.defineByte(DATA_SHARED_FLAGS_ID, (byte) 0);
        sed.defineShort(DATA_AIR_SUPPLY_ID, (short) 300);
        sed.defineFloat(DATA_HEALTH_ID, health);
        sed.defineInt(DATA_EFFECT_COLOR_ID, 0);
        sed.defineByte(DATA_EFFECT_AMBIENCE_ID, (byte) 0);
        sed.defineByte(DATA_ARROW_COUNT_ID, (byte) 0);
        sed.defineByte(DATA_PLAYER_FLAGS_ID, (byte) 0);
        sed.defineFloat(DATA_PLAYER_ABSORPTION_ID, 0.0f);
        sed.defineInt(DATA_SCORE_ID, 0);
        return sed;
    }

    private static int floorFixed32(double value) {
        return (int) Math.floor(value * 32.0);
    }

    private static int packRotationByte(float degrees) {
        return ((int) (degrees * 256.0f / 360.0f)) & 0xFF;
    }

    @Override
    public int getId() {
        return 20;
    }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        entityId = buf.readInt();
        name = buf.readLceUtf(20);
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        yRot = buf.readByte() & 0xFF;
        xRot = buf.readByte() & 0xFF;
        yHeadRot = buf.readByte() & 0xFF;
        carriedItem = buf.readShort();
        offlineXuid = buf.readPlayerUid();
        onlineXuid = buf.readPlayerUid();
        playerIndex = buf.readByte() & 0xFF;
        skinId = buf.readInt();
        capeId = buf.readInt();
        privileges = buf.readInt();
        packedEntityData = SynchedEntityData.readPackedBytes(buf);
        entityData = null;
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(entityId);
        buf.writeLceUtf(name);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(yRot);
        buf.writeByte(xRot);
        buf.writeByte(yHeadRot);
        buf.writeShort(carriedItem);
        buf.writePlayerUid(offlineXuid);
        buf.writePlayerUid(onlineXuid);
        buf.writeByte(playerIndex);
        buf.writeInt(skinId);
        buf.writeInt(capeId);
        buf.writeInt(privileges);
        if (entityData != null) {
            entityData.packAll(buf);
        } else if (packedEntityData != null) {
            buf.writeBytes(packedEntityData);
        } else {
            buf.writeByte(SynchedEntityData.EOF_MARKER);
        }
    }

    @Override
    protected int estimatedBodySize() {
        int packedLen = 1;
        if (packedEntityData != null) {
            packedLen = packedEntityData.length;
        } else if (entityData != null) {
            packedLen = (entityData.size() * 6) + 1;
        }
        return 4 + 2 + (name.length() * 2) + 12 + 3 + 2 + 16 + 1 + 12 + packedLen;
    }

    @Override
    public void handle(PacketListener listener) {
        throw new UnsupportedOperationException("AddPlayerPacket is server->client only");
    }
}

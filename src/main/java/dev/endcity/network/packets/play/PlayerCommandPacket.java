package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 19, C&rarr;S player control-state change.
 *
 * <p>Source: {@code Minecraft.World/PlayerCommandPacket.cpp}. Wire body:
 * {@code [Int entityId][Byte action][Int data]}.
 */
public final class PlayerCommandPacket extends Packet {

    public static final int START_SNEAKING = 1;
    public static final int STOP_SNEAKING = 2;
    public static final int STOP_SLEEPING = 3;
    public static final int START_SPRINTING = 4;
    public static final int STOP_SPRINTING = 5;
    public static final int START_IDLEANIM = 6;
    public static final int STOP_IDLEANIM = 7;
    public static final int RIDING_JUMP = 8;
    public static final int OPEN_INVENTORY = 9;

    public int entityId;
    public int action;
    public int data;

    public PlayerCommandPacket() {}

    public PlayerCommandPacket(int entityId, int action, int data) {
        this.entityId = entityId;
        this.action = action;
        this.data = data;
    }

    @Override
    public int getId() { return 19; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        entityId = buf.readInt();
        action = buf.readByte() & 0xFF;
        data = buf.readInt();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(entityId);
        buf.writeByte(action);
        buf.writeInt(data);
    }

    @Override
    protected int estimatedBodySize() {
        return 9;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handlePlayerCommand(this);
    }
}

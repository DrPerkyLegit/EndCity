package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 18, C<->S entity animation.
 *
 * <p>Source: {@code SOURCECODE/Minecraft.World/AnimatePacket.cpp}. Wire body:
 * {@code [Int entityId][Byte action]}.
 */
public final class AnimatePacket extends Packet {

    public static final int SWING = 1;
    public static final int HURT = 2;
    public static final int WAKE_UP = 3;
    public static final int RESPAWN = 4;
    public static final int EAT = 5;
    public static final int CRITICAL_HIT = 6;
    public static final int MAGIC_CRITICAL_HIT = 7;

    public int entityId;
    public int action;

    public AnimatePacket() {}

    public AnimatePacket(int entityId, int action) {
        this.entityId = entityId;
        this.action = action;
    }

    @Override
    public int getId() {
        return 18;
    }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        entityId = buf.readInt();
        action = buf.readByte() & 0xFF;
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(entityId);
        buf.writeByte(action);
    }

    @Override
    protected int estimatedBodySize() {
        return 5;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleAnimate(this);
    }
}

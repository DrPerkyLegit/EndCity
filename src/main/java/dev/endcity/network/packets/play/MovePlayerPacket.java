package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 10, C&rarr;S base movement flags packet.
 *
 * <p>Shared fields mirror {@code Minecraft.World/MovePlayerPacket.cpp}. Concrete variants add
 * position and/or rotation fields before the trailing flags byte.
 */
public class MovePlayerPacket extends Packet {

    public double x;
    public double y;
    public double yView;
    public double z;
    public float yRot;
    public float xRot;
    public boolean onGround;
    public boolean isFlying;
    public boolean hasPos;
    public boolean hasRot;

    public MovePlayerPacket() {}

    public MovePlayerPacket(boolean onGround, boolean isFlying) {
        this.onGround = onGround;
        this.isFlying = isFlying;
    }

    @Override
    public int getId() { return 10; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        byte flags = buf.readByte();
        onGround = (flags & 0x01) != 0;
        isFlying = (flags & 0x02) != 0;
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeByte((onGround ? 0x01 : 0) | (isFlying ? 0x02 : 0));
    }

    @Override
    protected int estimatedBodySize() {
        return 1;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleMovePlayer(this);
    }
}

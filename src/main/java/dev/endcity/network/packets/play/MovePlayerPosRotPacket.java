package dev.endcity.network.packets.play;

import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 13, S&rarr;C teleport / full local-player position sync.
 *
 * <p>Source: {@code Minecraft.World/MovePlayerPacket.cpp::PosRot::read/write}. The final flags byte
 * packs {@code onGround} into bit 0 and {@code isFlying} into bit 1.
 */
public final class MovePlayerPosRotPacket extends MovePlayerPacket {

    public MovePlayerPosRotPacket() {}

    public MovePlayerPosRotPacket(
            double x, double y, double yView, double z,
            float yRot, float xRot,
            boolean onGround, boolean isFlying) {
        super(onGround, isFlying);
        this.x = x;
        this.y = y;
        this.yView = yView;
        this.z = z;
        this.yRot = yRot;
        this.xRot = xRot;
        this.hasPos = true;
        this.hasRot = true;
    }

    @Override
    public int getId() { return 13; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        x = buf.readDouble();
        y = buf.readDouble();
        yView = buf.readDouble();
        z = buf.readDouble();
        yRot = buf.readFloat();
        xRot = buf.readFloat();
        super.read(buf);
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(yView);
        buf.writeDouble(z);
        buf.writeFloat(yRot);
        buf.writeFloat(xRot);
        super.write(buf);
    }

    @Override
    protected int estimatedBodySize() {
        return (8 * 4) + (4 * 2) + 1;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleMovePlayerPosRot(this);
    }
}

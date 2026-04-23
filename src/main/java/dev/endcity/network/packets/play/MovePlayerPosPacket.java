package dev.endcity.network.packets.play;

import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 11, C&rarr;S movement with position.
 */
public final class MovePlayerPosPacket extends MovePlayerPacket {

    public MovePlayerPosPacket() {
        hasPos = true;
    }

    public MovePlayerPosPacket(double x, double y, double yView, double z, boolean onGround, boolean isFlying) {
        super(onGround, isFlying);
        this.x = x;
        this.y = y;
        this.yView = yView;
        this.z = z;
        this.hasPos = true;
    }

    @Override
    public int getId() { return 11; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        x = buf.readDouble();
        y = buf.readDouble();
        yView = buf.readDouble();
        z = buf.readDouble();
        super.read(buf);
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(yView);
        buf.writeDouble(z);
        super.write(buf);
    }

    @Override
    protected int estimatedBodySize() {
        return (8 * 4) + 1;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleMovePlayerPos(this);
    }
}

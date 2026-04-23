package dev.endcity.network.packets.play;

import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 12, C&rarr;S movement with rotation.
 */
public final class MovePlayerRotPacket extends MovePlayerPacket {

    public MovePlayerRotPacket() {
        hasRot = true;
    }

    public MovePlayerRotPacket(float yRot, float xRot, boolean onGround, boolean isFlying) {
        super(onGround, isFlying);
        this.yRot = yRot;
        this.xRot = xRot;
        this.hasRot = true;
    }

    @Override
    public int getId() { return 12; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        yRot = buf.readFloat();
        xRot = buf.readFloat();
        super.read(buf);
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeFloat(yRot);
        buf.writeFloat(xRot);
        super.write(buf);
    }

    @Override
    protected int estimatedBodySize() {
        return 8 + 1;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleMovePlayerRot(this);
    }
}

package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 14, C->S player action.
 *
 * <p>Source: {@code SOURCECODE/Minecraft.World/PlayerActionPacket.cpp}. Wire body:
 * {@code [UnsignedByte action][Int x][UnsignedByte y][Int z][UnsignedByte face]}.
 */
public final class PlayerActionPacket extends Packet {

    public static final int START_DESTROY_BLOCK = 0;
    public static final int ABORT_DESTROY_BLOCK = 1;
    public static final int STOP_DESTROY_BLOCK = 2;
    public static final int DROP_ALL_ITEMS = 3;
    public static final int DROP_ITEM = 4;
    public static final int RELEASE_USE_ITEM = 5;

    public int action;
    public int x;
    public int y;
    public int z;
    public int face;

    public PlayerActionPacket() {}

    public PlayerActionPacket(int action, int x, int y, int z, int face) {
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.face = face;
    }

    @Override
    public int getId() {
        return 14;
    }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        action = buf.readByte() & 0xFF;
        x = buf.readInt();
        y = buf.readByte() & 0xFF;
        z = buf.readInt();
        face = buf.readByte() & 0xFF;
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeByte(action);
        buf.writeInt(x);
        buf.writeByte(y);
        buf.writeInt(z);
        buf.writeByte(face);
    }

    @Override
    protected int estimatedBodySize() {
        return 11;
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handlePlayerAction(this);
    }
}

package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 6, S&rarr;C only. Tells the client where the world's spawn point is (compass target).
 *
 * <p>Source: {@code Minecraft.World/SetSpawnPositionPacket.cpp::read/write} &mdash; three
 * {@code Int}s, all big-endian. 12 bytes body.
 *
 * <p>Wire body: {@code [Int x][Int y][Int z]}. Block coordinates; the client uses them to point
 * the compass and as the fallback respawn location if the player has no bed.
 *
 * <p>Server-only. See class docs on {@link SetTimePacket} for why {@link #handle} throws.
 */
public final class SetSpawnPositionPacket extends Packet {

    public int x;
    public int y;
    public int z;

    public SetSpawnPositionPacket() {}

    public SetSpawnPositionPacket(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int getId() { return 6; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException(
                "SetSpawnPositionPacket is server-only; never dispatched inbound");
    }
}

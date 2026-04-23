package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 155, S&rarr;C only. Batch-visibility packet emitted once at login to establish the
 * client's initial chunk render window, replacing what would otherwise be {@code (maxX-minX+1) *
 * (maxZ-minZ+1)} individual {@link ChunkVisibilityPacket}s (4J optimization).
 *
 * <p>Source: {@code Minecraft.World/ChunkVisibilityAreaPacket.cpp::read/write}:
 * <pre>
 *   writeInt(minX);
 *   writeInt(maxX);
 *   writeInt(minZ);
 *   writeInt(maxZ);
 * </pre>
 * 16 bytes body.
 *
 * <p>Field order is pair-wise by axis &mdash; {@code minX, maxX, minZ, maxZ}, NOT
 * {@code minX, minZ, maxX, maxZ}. Getting this wrong produces a silently bogus visibility window
 * (client thinks the world is elsewhere and never asks for chunks in range).
 *
 * <p>Bounds are inclusive chunk coordinates. Typical login-time window: chunk radius 8 around
 * spawn, so {@code minCX=-8, maxCX=8, minCZ=-8, maxCZ=8}.
 *
 * <p>Server-only. See class docs on {@link SetTimePacket} for why {@link #handle} throws.
 */
public final class ChunkVisibilityAreaPacket extends Packet {

    public int minX;
    public int maxX;
    public int minZ;
    public int maxZ;

    public ChunkVisibilityAreaPacket() {}

    public ChunkVisibilityAreaPacket(int minX, int maxX, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    @Override
    public int getId() { return 155; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        minX = buf.readInt();
        maxX = buf.readInt();
        minZ = buf.readInt();
        maxZ = buf.readInt();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(minX);
        buf.writeInt(maxX);
        buf.writeInt(minZ);
        buf.writeInt(maxZ);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException(
                "ChunkVisibilityAreaPacket is server-only; never dispatched inbound");
    }
}

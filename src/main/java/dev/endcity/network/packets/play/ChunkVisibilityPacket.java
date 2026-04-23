package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 50, S&rarr;C only. Makes a single chunk visible or invisible on the client side.
 *
 * <p>Source: {@code Minecraft.World/ChunkVisibilityPacket.cpp::read/write}:
 * <pre>
 *   writeInt(x);
 *   writeInt(z);
 *   write(visible ? 1 : 0);  // 1 byte
 * </pre>
 * 9 bytes body.
 *
 * <p>{@code x} and {@code z} are chunk coordinates (block coords &gt;&gt; 4), not block coords.
 * {@code visible=true} tells the client to expect chunk data for this chunk; {@code visible=false}
 * evicts it from the client's render list. The batch equivalent for login-time streaming is
 * {@link ChunkVisibilityAreaPacket}.
 *
 * <p>Server-only. See class docs on {@link SetTimePacket} for why {@link #handle} throws.
 */
public final class ChunkVisibilityPacket extends Packet {

    public int x;
    public int z;
    public boolean visible;

    public ChunkVisibilityPacket() {}

    public ChunkVisibilityPacket(int x, int z, boolean visible) {
        this.x = x;
        this.z = z;
        this.visible = visible;
    }

    @Override
    public int getId() { return 50; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        x = buf.readInt();
        z = buf.readInt();
        visible = buf.readByte() != 0;
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeByte(visible ? 1 : 0);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException(
                "ChunkVisibilityPacket is server-only; never dispatched inbound");
    }
}

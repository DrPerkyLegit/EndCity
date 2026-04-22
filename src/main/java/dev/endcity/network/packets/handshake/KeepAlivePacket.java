package dev.endcity.network.packets.handshake;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 0, ⇌. Single Int token used for heartbeat + latency estimation.
 *
 * <p>Source: {@code Minecraft.World/KeepAlivePacket.cpp::read/write} — just {@code readInt()} /
 * {@code writeInt(id)}. The field name is {@code id} in the source; we call it {@code token} here
 * to avoid collision with {@link #getId()} (the packet type id).
 *
 * <p>Semantics (from {@code Minecraft.Client/PlayerConnection.cpp:1368-1374}): the server emits a
 * KeepAlive every second with a fresh random token; the client echoes it back; when the server
 * receives the matching echo it computes latency. Liveness (timeout detection) is driven by any
 * byte arrival, not specifically by matching echoes.
 */
public final class KeepAlivePacket extends Packet {

    public int token;

    public KeepAlivePacket() {
        this.token = 0;
    }

    public KeepAlivePacket(int token) {
        this.token = token;
    }

    @Override
    public int getId() { return 0; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        this.token = buf.readInt();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(token);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleKeepAlive(this);
    }
}

package dev.endcity.network.packets.handshake;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 255, ⇌. Single field: the {@code eDisconnectReason} enum as a 4-byte big-endian Int.
 *
 * <p>Source: {@code Minecraft.World/DisconnectPacket.cpp::read/write} — just {@code readInt()} /
 * {@code writeInt((int)reason)}. Enum ordinals come from
 * {@code Minecraft.World/DisconnectPacket.h::eDisconnectReason}.
 *
 * <p>Reason values are not modeled as a Java enum here (M1+ may want to extend the set), but
 * {@link NetworkConstants.DisconnectReason} has named constants for the values we actually emit.
 */
public final class DisconnectPacket extends Packet {

    public int reason;

    public DisconnectPacket() {
        this.reason = NetworkConstants.DisconnectReason.NONE;
    }

    public DisconnectPacket(int reason) {
        this.reason = reason;
    }

    @Override
    public int getId() { return NetworkConstants.DISCONNECT_PACKET_ID; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        this.reason = buf.readInt();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeInt(reason);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        listener.handleDisconnect(this);
    }
}

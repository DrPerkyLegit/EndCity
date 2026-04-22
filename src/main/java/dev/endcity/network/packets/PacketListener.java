package dev.endcity.network.packets;

import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;

import java.io.IOException;

/**
 * Dispatch interface for received packets. Matches the shape of {@code Minecraft.World/PacketListener.h}
 * (each packet type has a {@code handleXxx} method) but trimmed to the packets we currently decode.
 *
 * <p>All methods are {@code default} and fall through to {@link #onUnhandledPacket(Packet)}, which a
 * stateful listener (e.g. the per-connection dispatcher) can override to reject packets arriving in
 * the wrong {@code ConnectionState}.
 */
public interface PacketListener {

    /** Called when a packet arrives that this listener does not explicitly handle. */
    default void onUnhandledPacket(Packet packet) throws IOException {
        throw new IOException("unhandled packet id=" + packet.getId()
                + " type=" + packet.getClass().getSimpleName());
    }

    default void handlePreLogin(PreLoginPacket packet)  throws IOException { onUnhandledPacket(packet); }
    default void handleLogin(LoginPacket packet)        throws IOException { onUnhandledPacket(packet); }
    default void handleDisconnect(DisconnectPacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handleKeepAlive(KeepAlivePacket packet) throws IOException { onUnhandledPacket(packet); }
}

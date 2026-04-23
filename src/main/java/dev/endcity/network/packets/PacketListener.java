package dev.endcity.network.packets;

import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;
import dev.endcity.network.packets.play.AnimatePacket;
import dev.endcity.network.packets.play.PlayerActionPacket;
import dev.endcity.network.packets.play.MovePlayerPacket;
import dev.endcity.network.packets.play.MovePlayerPosPacket;
import dev.endcity.network.packets.play.MovePlayerPosRotPacket;
import dev.endcity.network.packets.play.MovePlayerRotPacket;
import dev.endcity.network.packets.play.PlayerCommandPacket;

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
    default void handleAnimate(AnimatePacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handlePlayerAction(PlayerActionPacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handleMovePlayer(MovePlayerPacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handleMovePlayerPos(MovePlayerPosPacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handleMovePlayerRot(MovePlayerRotPacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handleMovePlayerPosRot(MovePlayerPosRotPacket packet) throws IOException { onUnhandledPacket(packet); }
    default void handlePlayerCommand(PlayerCommandPacket packet) throws IOException { onUnhandledPacket(packet); }
}

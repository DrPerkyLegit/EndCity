package dev.endcity.network.connection;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.NetworkManager;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-connection {@link PacketListener} that enforces the {@link ConnectionState} gate (§4.3) and
 * routes accepted packets to handler methods. Any packet arriving in the wrong state results in an
 * {@code eDisconnect_UnexpectedPacket}.
 *
 * <p>Thread model: instantiated per-{@link PlayerConnection} and invoked only from the owning
 * {@code ConnectionThread} via {@link PlayerConnection#decodeAndDispatch}. All state mutation
 * (sending packets, marking closing, transitioning state) happens on that thread.
 */
public final class ServerPacketListener implements PacketListener {

    private static final Logger LOGGER = Logger.getLogger(ServerPacketListener.class.getName());

    private final PlayerConnection connection;
    private final NetworkManager networkManager;

    public ServerPacketListener(PlayerConnection connection, NetworkManager networkManager) {
        this.connection = connection;
        this.networkManager = networkManager;
    }

    // -------------------------------------------------------------- state gate

    /**
     * Check whether {@code packet} is legal in the current {@link ConnectionState}. If not, logs,
     * emits an {@code eDisconnect_UnexpectedPacket}, and returns {@code false}.
     *
     * <p>Legality matrix:
     * <ul>
     *   <li>{@code Pending}: {@link PreLoginPacket} (2), {@link DisconnectPacket} (255)</li>
     *   <li>{@code Login}:   {@link LoginPacket} (1),    {@link DisconnectPacket} (255)</li>
     *   <li>{@code Play}:    any packet registered as {@code receiveOnServer=true}</li>
     * </ul>
     */
    private boolean gate(Packet packet) {
        ConnectionState s = connection.state();
        int id = packet.getId();
        boolean ok = switch (s) {
            case Pending -> id == 2 /*PreLogin*/ || id == 255 /*Disconnect*/;
            case Login   -> id == 1 /*Login*/    || id == 255 /*Disconnect*/;
            case Play    -> true;
        };
        if (!ok) {
            LOGGER.log(Level.WARNING,
                    "{0} unexpected packet id={1} in state {2}; disconnecting",
                    new Object[] { connection.logTag(), id, s });
            sendDisconnect(NetworkConstants.DisconnectReason.UNEXPECTED_PACKET);
            return false;
        }
        return true;
    }

    /** Emit a {@code DisconnectPacket} with the given reason, then flag the connection as closing. */
    private void sendDisconnect(int reason) {
        connection.sendPacket(new DisconnectPacket(reason));
        connection.markClosing();
    }

    // -------------------------------------------------------------- PreLogin → Login transition

    @Override
    public void handlePreLogin(PreLoginPacket packet) {
        if (!gate(packet)) return;

        LOGGER.log(Level.INFO,
                "{0} PreLogin: netVer={1} loginKey={2} playerCount={3}",
                new Object[] { connection.logTag(), packet.netcodeVersion, packet.loginKey, packet.playerCount });

        // Validate protocol version. Source doesn't validate here; we do, to fail fast with a
        // meaningful reason instead of letting a mismatched client carry on and desync.
        int theirVer = packet.netcodeVersion & 0xFFFF;
        int ourVer = NetworkConstants.MINECRAFT_NET_VERSION;
        if (theirVer < ourVer) {
            LOGGER.log(Level.INFO, "{0} client netVer {1} < server {2}; OutdatedClient",
                    new Object[] { connection.logTag(), theirVer, ourVer });
            sendDisconnect(NetworkConstants.DisconnectReason.OUTDATED_CLIENT);
            return;
        }
        if (theirVer > ourVer) {
            LOGGER.log(Level.INFO, "{0} client netVer {1} > server {2}; OutdatedServer",
                    new Object[] { connection.logTag(), theirVer, ourVer });
            sendDisconnect(NetworkConstants.DisconnectReason.OUTDATED_SERVER);
            return;
        }

        // No split-screen (see PLAN.md §7.7). Exactly one XUID expected.
        if (packet.playerCount != 1) {
            LOGGER.log(Level.INFO, "{0} playerCount={1}, split-screen not supported",
                    new Object[] { connection.logTag(), packet.playerCount });
            sendDisconnect(NetworkConstants.DisconnectReason.NO_MULTIPLAYER_PRIVILEGES_JOIN);
            return;
        }

        // Construct the server-side LoginPacket and send. Field values per PLAN.md §3 M1.
        LoginPacket response = new LoginPacket();
        response.clientVersion         = NetworkConstants.NETWORK_PROTOCOL_VERSION;    // 78
        response.userName              = packet.loginKey;
        response.levelTypeName         = "default";                                    // placeholder until world gen
        response.seed                  = 0L;                                           // placeholder
        response.gameType              = 0;                                            // Survival
        response.dimension             = 0;                                            // Overworld
        response.mapHeight             = 0;                                            // (byte)256 per source
        response.maxPlayers            = (byte) networkManager.maxPlayers();
        response.offlineXuid           = NetworkConstants.INVALID_XUID;
        response.onlineXuid            = NetworkConstants.INVALID_XUID;
        response.friendsOnlyUGC        = false;
        response.ugcPlayersVersion     = 0;
        response.difficulty            = 1;                                            // Easy
        response.multiplayerInstanceId = networkManager.multiplayerInstanceId();
        response.playerIndex           = (byte) connection.smallId();
        response.playerSkinId          = 0;
        response.playerCapeId          = 0;
        response.isGuest               = false;
        response.newSeaLevel           = true;
        response.uiGamePrivileges      = 0;
        response.xzSize                = NetworkConstants.LEVEL_MAX_WIDTH;             // 320
        response.hellScale             = NetworkConstants.HELL_LEVEL_MAX_SCALE;        // 8

        connection.sendPacket(response);
        connection.transitionTo(ConnectionState.Login);
        LOGGER.log(Level.INFO, "{0} PreLogin OK; server LoginPacket sent (userName={1})",
                new Object[] { connection.logTag(), response.userName });
    }

    // -------------------------------------------------------------- Login → Play transition

    @Override
    public void handleLogin(LoginPacket packet) {
        if (!gate(packet)) return;

        LOGGER.log(Level.INFO,
                "{0} Login (C->S): userName={1} offlineXuid=0x{2} onlineXuid=0x{3} skin={4} cape={5}",
                new Object[] {
                        connection.logTag(),
                        packet.userName,
                        Long.toHexString(packet.offlineXuid),
                        Long.toHexString(packet.onlineXuid),
                        packet.playerSkinId,
                        packet.playerCapeId
                });

        // No validation necessary for M1 — any structurally-valid LoginPacket from the client
        // completes the handshake. Later milestones will cross-check offline/online XUIDs and skin
        // ids against whitelists/bans/etc.
        connection.transitionTo(ConnectionState.Play);
        LOGGER.log(Level.INFO, "{0} handshake complete, entered Play", connection.logTag());
        // Keep-alive scheduling is chunk 8.
    }

    // -------------------------------------------------------------- KeepAlive

    @Override
    public void handleKeepAlive(KeepAlivePacket packet) {
        if (!gate(packet)) return;
        // Any byte arrival already bumped lastPacketReceivedAtNanos in ConnectionThread.handleRead.
        // The token match is only used for latency computation (chunk 8+).
        LOGGER.log(Level.FINEST, "{0} KeepAlive token=0x{1}",
                new Object[] { connection.logTag(), Integer.toHexString(packet.token) });
    }

    // -------------------------------------------------------------- Disconnect

    @Override
    public void handleDisconnect(DisconnectPacket packet) {
        // Client-initiated disconnect: always legal. Log the reason and tear down.
        LOGGER.log(Level.INFO, "{0} client disconnected, reason={1}",
                new Object[] { connection.logTag(), packet.reason });
        connection.markDisconnected();
    }
}

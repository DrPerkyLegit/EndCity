package dev.endcity.network.connection;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.NetworkManager;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;
import dev.endcity.network.packets.play.AddPlayerPacket;
import dev.endcity.network.packets.play.AnimatePacket;
import dev.endcity.network.packets.play.BlockRegionUpdatePacket;
import dev.endcity.network.packets.play.ChunkVisibilityAreaPacket;
import dev.endcity.network.packets.play.ChunkVisibilityPacket;
import dev.endcity.network.packets.play.MovePlayerPacket;
import dev.endcity.network.packets.play.MovePlayerPosPacket;
import dev.endcity.network.packets.play.MovePlayerPosRotPacket;
import dev.endcity.network.packets.play.MovePlayerRotPacket;
import dev.endcity.network.packets.play.PlayerActionPacket;
import dev.endcity.network.packets.play.PlayerAbilitiesPacket;
import dev.endcity.network.packets.play.PlayerCommandPacket;
import dev.endcity.network.packets.play.SetHealthPacket;
import dev.endcity.network.packets.play.SetSpawnPositionPacket;
import dev.endcity.network.packets.play.SetTimePacket;
import dev.endcity.world.chunk.FlatChunk;
import dev.endcity.world.chunk.FlatChunkGenerator;
import dev.endcity.world.entity.SynchedEntityData;

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
     * <p>Legality matrix, source-verified against {@code Minecraft.Client/PendingConnection.cpp}.
     * The source has a single listener ({@code PendingConnection}) handling both the pre-login and
     * login phases — both PreLogin and Login are acceptable in either of our {@code Pending} and
     * {@code Login} states. The handshake sequence is:
     * <ol>
     *   <li>{@code C->S PreLogin} (id=2) — handled in {@code Pending}; server replies with
     *       {@code S->C PreLoginResponse} (also id=2) and stays in {@code Pending}.</li>
     *   <li>{@code C->S Login} (id=1) — handled in {@code Pending}; server transitions to
     *       {@code Login}, processes, replies with {@code S->C LoginResponse} (id=1), and
     *       transitions to {@code Play}.</li>
     * </ol>
     *
     * <ul>
     *   <li>{@code Pending}: {@link PreLoginPacket} (2), {@link LoginPacket} (1),
     *       {@link KeepAlivePacket} (0), {@link DisconnectPacket} (255). KeepAlive is silently
     *       ignored by the source's {@code handleKeepAlive} — treating it as an unexpected packet
     *       kicks real clients because they do send keep-alives during handshake.</li>
     *   <li>{@code Login}: {@link KeepAlivePacket} (0), {@link DisconnectPacket} (255). This state
     *       is transient — the server is building and sending the LoginResponse. The client
     *       shouldn't normally send anything new here.</li>
     *   <li>{@code Play}: any packet registered as {@code receiveOnServer=true}.</li>
     * </ul>
     */
    private boolean gate(Packet packet) {
        ConnectionState s = connection.state();
        int id = packet.getId();
        boolean ok = switch (s) {
            case Pending -> id == 2 /*PreLogin*/ || id == 1 /*Login*/
                         || id == 0 /*KeepAlive*/ || id == 255 /*Disconnect*/;
            case Login   -> id == 0 /*KeepAlive*/ || id == 255 /*Disconnect*/;
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

        // playerCount is informational, not gating. The real LCE Win64 client sends 0 for
        // single-player remote join; the reference dedicated server
        // (.MinecraftLegacyEdition/server/src/core/PacketHandler.cpp::ReadPreLogin) reads the
        // field but does no validation on it. PLAN.md §7.7 guessed this was split-screen
        // gating — it's not, and kicking on playerCount != 1 refuses every real client.
        // For M1 we accept any value; proper split-screen handling is deferred.
        if (packet.playerCount > 1) {
            LOGGER.log(Level.INFO, "{0} playerCount={1} (split-screen, treated as single player)",
                    new Object[] { connection.logTag(), packet.playerCount });
        }

        // Build the server-side PreLoginResponse. Values mirror
        // .MinecraftLegacyEdition/server/src/core/PacketHandler.cpp::WritePreLoginResponse and the
        // source's PendingConnection::sendPreLoginResponse. This is what the client actually
        // expects back after its own PreLogin — NOT a LoginPacket. Sending LoginPacket here leaves
        // the client stuck in its equivalent of "Pending" forever (we observed this: black screen,
        // no C->S Login ever sent, 30s watchdog fires).
        //
        // Key fields:
        //   loginKey         = "-"  (convention: non-online-mode server)
        //   playerXuids      = []   (no existing UGC players on empty server)
        //   uniqueSaveName   = 14 zero bytes (placeholder until real world load in M2)
        //   serverSettings   = 0    (no UGC restrictions; M3+ fills this in)
        //
        // State does NOT transition here — we stay in Pending waiting for the client's Login.
        PreLoginPacket response = new PreLoginPacket();
        response.netcodeVersion    = (short) NetworkConstants.MINECRAFT_NET_VERSION;
        response.loginKey          = "-";
        response.friendsOnlyBits   = 0;
        response.ugcPlayersVersion = 0;
        response.playerCount       = 0;
        response.playerXuids       = new long[0];
        // uniqueSaveName is already zero-initialized
        response.serverSettings    = 0;
        response.hostIndex         = 0;
        response.texturePackId     = 0;

        connection.sendPacket(response);
        LOGGER.log(Level.INFO, "{0} PreLogin OK; server PreLoginResponse sent, awaiting client Login",
                new Object[] { connection.logTag() });
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

        // Enter Login state briefly while we build and send the response.
        connection.transitionTo(ConnectionState.Login);

        // Build server-side LoginResponse. Mirrors the reference server's WriteLoginResponse
        // (.MinecraftLegacyEdition/server/src/core/PacketHandler.cpp) and the source's
        // LoginPacket::write. In S->C direction:
        //   - clientVersion field carries the assigned entityId (NOT the protocol version).
        //     This is a source quirk: LoginPacket::m_iClientVersion is reused.
        //   - userName echoes the client's chosen name.
        //   - offlineXuid and onlineXuid are INVALID_XUID (server doesn't have player-scoped
        //     UIDs in offline mode).
        //   - playerIndex is the connection's smallId.
        //   - _LARGE_WORLDS trailing fields (xzSize, hellScale) are always present on Win64.
        LoginPacket response = new LoginPacket();
        response.clientVersion         = connection.smallId();                         // entityId in S->C
        response.userName              = packet.userName != null && !packet.userName.isEmpty()
                                          ? packet.userName
                                          : "-";
        response.levelTypeName         = "default";
        response.seed                  = 0L;
        response.gameType              = 0;                                            // Survival
        response.dimension             = 0;                                            // Overworld
        response.mapHeight             = 0;                                            // source writes (byte)256
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
        connection.transitionTo(ConnectionState.Play);
        LOGGER.log(Level.INFO, "{0} handshake complete, entered Play (userName={1}, entityId={2})",
                new Object[] { connection.logTag(), response.userName, response.clientVersion });

        // ---------------------------------------------------------- M2.3 post-Login world setup
        // Streams the minimal flat chunk window after the fixed play-phase setup. Order mirrors the
        // reference server (.MinecraftLegacyEdition/server/src/core/Connection.cpp post-login):
        // SetTime, SetSpawnPosition, SetHealth, PlayerAbilities, ChunkVisibilityArea, then chunks.
        //
        // Chunk data (BlockRegionUpdate id=51) is M2.3 — without it the client has no terrain to
        // render. The expected outcome after this burst is visible flat terrain if the client
        // accepts the BlockRegionUpdate RLE+zlib payloads.
        //
        // Values: midday, spawn at (8,5,8), full health/food, survival abilities, 3x3 chunk
        // visibility window populated by the matching send loop below.
        connection.sendPacket(new SetSpawnPositionPacket(8, 5, 8));
        connection.sendPacket(new PlayerAbilitiesPacket(
                (byte) 0,
                PlayerAbilitiesPacket.DEFAULT_FLYING_SPEED,
                PlayerAbilitiesPacket.DEFAULT_WALKING_SPEED));
        connection.sendPacket(new SetTimePacket(0L, 6000L));
        connection.sendPacket(new ChunkVisibilityAreaPacket(-1, 1, -1, 1));
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                FlatChunk chunk = FlatChunkGenerator.generate(cx, cz);
                connection.sendPacket(new ChunkVisibilityPacket(cx, cz, true));
                connection.sendPacket(new BlockRegionUpdatePacket(
                        cx * 16, 0, cz * 16,
                        16, chunk.ys(), 16,
                        0, true,
                        chunk.rawData()));
            }
        }
        double spawnFeetY = 5.00001;
        connection.sendPacket(new MovePlayerPosRotPacket(
                8.5, spawnFeetY + 1.62, spawnFeetY, 8.5,
                0.0f, 0.0f,
                true, false));
        connection.sendPacket(new SetHealthPacket(20.0f, (short) 20, 5.0f, SetHealthPacket.DAMAGE_SOURCE_UNKNOWN));
        SynchedEntityData addPlayerMetadata = AddPlayerPacket.defaultPlayerEntityData(20.0f);
        connection.sendPacket(AddPlayerPacket.fromWorldState(
                response.clientVersion,
                response.userName,
                8.5, spawnFeetY, 8.5,
                0.0f, 0.0f, 0.0f,
                (short) 0,
                packet.offlineXuid,
                packet.onlineXuid,
                connection.smallId(),
                packet.playerSkinId,
                packet.playerCapeId,
                response.uiGamePrivileges,
                addPlayerMetadata));
        LOGGER.log(Level.INFO,
                "{0} M2.4 post-Login world packets sent (setup + 3x3 BlockRegionUpdate flat chunks + local teleport + AddPlayer)",
                new Object[] { connection.logTag() });
    }

    // -------------------------------------------------------------- KeepAlive

    /**
     * Server-side KeepAlive handling.
     *
     * <p>In {@code Pending}/{@code Login}: source ({@code PendingConnection.cpp:358}) silently
     * ignores these. Real clients emit keep-alives during the handshake phase; rejecting them with
     * UNEXPECTED_PACKET kicks live clients immediately after the small-ID handshake.
     *
     * <p>In {@code Play}: liveness is already maintained via {@code markActivity()} on any byte
     * arrival. Matching the echoed token against the last-sent token to compute round-trip latency
     * is a chunk 8+ feature (see {@code Minecraft.Client/PlayerConnection.cpp:1370}). For now we
     * just log.
     */
    @Override
    public void handleKeepAlive(KeepAlivePacket packet) {
        if (!gate(packet)) return;
        LOGGER.log(Level.FINEST, "{0} KeepAlive token=0x{1} (state={2})",
                new Object[] { connection.logTag(), Integer.toHexString(packet.token), connection.state() });
    }

    // -------------------------------------------------------------- movement

    @Override
    public void handleAnimate(AnimatePacket packet) {
        if (!gate(packet)) return;
    }

    @Override
    public void handlePlayerAction(PlayerActionPacket packet) {
        if (!gate(packet)) return;
    }

    @Override
    public void handleMovePlayer(MovePlayerPacket packet) {
        if (!gate(packet)) return;
    }

    @Override
    public void handleMovePlayerPos(MovePlayerPosPacket packet) {
        if (!gate(packet)) return;
    }

    @Override
    public void handleMovePlayerRot(MovePlayerRotPacket packet) {
        if (!gate(packet)) return;
    }

    @Override
    public void handleMovePlayerPosRot(MovePlayerPosRotPacket packet) {
        if (!gate(packet)) return;
    }

    @Override
    public void handlePlayerCommand(PlayerCommandPacket packet) {
        if (!gate(packet)) return;
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

package dev.endcity.network;

/**
 * Transport-layer constants for the LCE Win64 wire protocol.
 *
 * <p>All values are ground-truth from {@code SOURCECODE/Minecraft.Client/Windows64/Network/WinsockNetLayer.h}
 * and {@code SOURCECODE/Minecraft.World/DisconnectPacket.h}. When the design doc and the source disagree,
 * the source wins. Do not change these without re-reading the referenced C++ files.
 */
public final class NetworkConstants {

    private NetworkConstants() {}

    /** First small ID assignable to a remote connection. IDs 0..3 are reserved for local split-screen users. */
    public static final int XUSER_MAX_COUNT = 4;

    /** Upper bound (exclusive) on small IDs on Win64. See {@code Minecraft.World/x64headers/extraX64.h}. */
    public static final int MINECRAFT_NET_MAX_PLAYERS = 256;

    /** Hard socket cap (not the same thing as the player cap). */
    public static final int WIN64_NET_MAX_CLIENTS = 255;

    /** Sentinel first byte indicating the server is rejecting this connection. */
    public static final byte WIN64_SMALLID_REJECT = (byte) 0xFF;

    /** Per-socket recv buffer size. Mirrors {@code WIN64_NET_RECV_BUFFER_SIZE}. */
    public static final int WIN64_NET_RECV_BUFFER_SIZE = 65536;

    /** Absolute per-packet upper bound (4 MiB). Mirrors {@code WIN64_NET_MAX_PACKET_SIZE}. */
    public static final int WIN64_NET_MAX_PACKET_SIZE = 4 * 1024 * 1024;

    /** Default TCP port for the game. */
    public static final int WIN64_NET_DEFAULT_PORT = 25565;

    /** Initial size of the per-connection inbound accumulator buffer. Grows up to {@link #WIN64_NET_MAX_PACKET_SIZE}. */
    public static final int INBOUND_INITIAL_CAPACITY = 8 * 1024;

    /** DisconnectPacket wire id. See {@code DisconnectPacket::getId()}. */
    public static final int DISCONNECT_PACKET_ID = 255;

    /**
     * Wire protocol version the client advertises in {@code PreLoginPacket.m_netcodeVersion}. Mismatch
     * triggers {@code eDisconnect_OutdatedClient} / {@code eDisconnect_OutdatedServer}.
     * From {@code include/Common/BuildVer.h} ({@code VER_PRODUCTBUILD} / {@code VER_NETWORK} / {@code MINECRAFT_NET_VERSION}).
     */
    public static final int MINECRAFT_NET_VERSION = 560;

    /**
     * Shared-constants protocol version the server advertises in {@code LoginPacket.clientVersion}.
     * From {@code Minecraft.World/SharedConstants.h::NETWORK_PROTOCOL_VERSION}. <strong>Not the same as
     * {@link #MINECRAFT_NET_VERSION}.</strong>
     */
    public static final int NETWORK_PROTOCOL_VERSION = 78;

    /**
     * Max chars for {@code LoginPacket.userName} and by extension any player name.
     * From {@code Minecraft.World/Player.h::MAX_NAME_LENGTH} (= 16 + 4).
     */
    public static final int PLAYER_MAX_NAME_LENGTH = 20;

    /**
     * Max chars for a level-type generator name (e.g. {@code "default"}, {@code "flat"}).
     * From the hard-coded literal in {@code LoginPacket.cpp::read} ({@code readUtf(dis, 16)}).
     */
    public static final int LEVEL_TYPE_NAME_MAX_LEN = 16;

    /**
     * Largest world width the Win64 build supports, in chunks. Emitted in the {@code _LARGE_WORLDS}
     * tail of {@code LoginPacket}. From {@code Minecraft.World/ChunkSource.h::LEVEL_MAX_WIDTH}
     * ({@code 5 * 64 = 320}) with {@code _LARGE_WORLDS} defined.
     */
    public static final int LEVEL_MAX_WIDTH = 320;

    /**
     * Nether coordinate scale on Win64. Emitted in the {@code _LARGE_WORLDS} tail of {@code LoginPacket}.
     * From {@code Minecraft.World/ChunkSource.h::HELL_LEVEL_MAX_SCALE} with {@code _LARGE_WORLDS} defined.
     */
    public static final int HELL_LEVEL_MAX_SCALE = 8;

    /** Special sentinel player-UID value. From {@code Common/Network/NetworkPlayerInterface.h::INVALID_XUID}. */
    public static final long INVALID_XUID = 0L;

    /**
     * Ordinals of {@code DisconnectPacket::eDisconnectReason} from the source. Only the subset used
     * through M1 (plus a handful of neighbours we'll need soon) is defined here; the rest land as
     * they become needed in later milestones.
     */
    public static final class DisconnectReason {
        private DisconnectReason() {}
        public static final int NONE                             = 0;
        public static final int QUITTING                         = 1;
        public static final int CLOSED                           = 2;
        public static final int LOGIN_TOO_LONG                   = 3;
        public static final int KICKED                           = 8;
        public static final int TIME_OUT                         = 9;
        public static final int OVERFLOW_                        = 10; // trailing _ to avoid keyword clash if we ever make it an enum
        public static final int END_OF_STREAM                    = 11;
        public static final int SERVER_FULL                      = 12;
        public static final int OUTDATED_SERVER                  = 13; // client's net version > server's
        public static final int OUTDATED_CLIENT                  = 14; // client's net version < server's
        public static final int UNEXPECTED_PACKET                = 15;
        public static final int NO_MULTIPLAYER_PRIVILEGES_JOIN   = 18;
    }
}

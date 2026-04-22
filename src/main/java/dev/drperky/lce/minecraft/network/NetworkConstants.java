package dev.drperky.lce.minecraft.network;

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
     * Ordinals of {@code DisconnectPacket::eDisconnectReason} from the source. Only the subset used in M0
     * is defined here; the rest land with {@code DisconnectPacket} in M1.
     */
    public static final class DisconnectReason {
        private DisconnectReason() {}
        public static final int NONE               = 0;
        public static final int QUITTING           = 1;
        public static final int CLOSED             = 2;
        public static final int LOGIN_TOO_LONG     = 3;
        public static final int TIME_OUT           = 9;
        public static final int SERVER_FULL        = 12;
        public static final int UNEXPECTED_PACKET  = 15;
    }
}

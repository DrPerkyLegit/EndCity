package dev.endcity.network.packets;

import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;
import dev.endcity.network.packets.play.ChunkVisibilityAreaPacket;
import dev.endcity.network.packets.play.ChunkVisibilityPacket;
import dev.endcity.network.packets.play.PlayerAbilitiesPacket;
import dev.endcity.network.packets.play.SetHealthPacket;
import dev.endcity.network.packets.play.SetSpawnPositionPacket;
import dev.endcity.network.packets.play.SetTimePacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Packet id → factory registry + receive/send policy sets. Mirrors the {@code map(id, ..., create)}
 * calls in {@code Minecraft.World/Packet.cpp::staticCtor()}.
 *
 * <p>Only the packets we've implemented are registered. Any incoming packet whose id is not in the
 * {@link #SERVER_RECEIVED} set triggers a disconnect — see the decoder on {@code PlayerConnection}.
 */
public final class Packets {

    private Packets() {}

    private static final Map<Integer, Supplier<? extends Packet>> FACTORIES = new HashMap<>();
    private static final Set<Integer> SERVER_RECEIVED = new HashSet<>();
    @SuppressWarnings("unused") // populated now, consumed in M6+ when broadcast logic arrives
    private static final Set<Integer> SEND_TO_ANY_CLIENT = new HashSet<>();

    static {
        // The tuple (receiveOnClient, receiveOnServer, sendToAnyClient) mirrors the source's
        // Packet::map(id, rcvClient, rcvServer, sendToAny, renderStats, ...). We only care about
        // receiveOnServer and sendToAnyClient — rcvClient and renderStats are client/debug only.

        // id, factory, receiveOnServer, sendToAnyClient
        map(0,   KeepAlivePacket::new,  true,  true);  // map(0,  true,true,true, false, KeepAlivePacket)
        map(1,   LoginPacket::new,      true,  true);  // map(1,  true,true,true, false, LoginPacket)
        map(2,   PreLoginPacket::new,   true,  true);  // map(2,  true,true,true, false, PreLoginPacket)
        map(255, DisconnectPacket::new, true,  true);  // map(255,true,true,true, false, DisconnectPacket)

        // M2 play-phase packets. All server-outbound only; client never sends these to us, so
        // receiveOnServer=false. If a misbehaving or malicious client sends one, the decoder
        // throws UnknownPacketIdException and the gate disconnects with UnexpectedPacket.
        map(4,   SetTimePacket::new,             false, true);  // [Long gameTime][Long dayTime]
        map(6,   SetSpawnPositionPacket::new,    false, true);  // [Int x][Int y][Int z]
        map(8,   SetHealthPacket::new,           false, true);  // [Float h][Short food][Float sat][Byte dmg]
        map(50,  ChunkVisibilityPacket::new,     false, true);  // [Int x][Int z][Byte visible]
        map(155, ChunkVisibilityAreaPacket::new, false, true);  // [Int minX][Int maxX][Int minZ][Int maxZ]
        map(202, PlayerAbilitiesPacket::new,     false, true);  // [Byte flags][Float flySp][Float walkSp]
    }

    private static void map(int id, Supplier<? extends Packet> factory,
                            boolean receiveOnServer, boolean sendToAnyClient) {
        if (FACTORIES.containsKey(id)) {
            throw new IllegalStateException("duplicate packet id " + id);
        }
        FACTORIES.put(id, factory);
        if (receiveOnServer) SERVER_RECEIVED.add(id);
        if (sendToAnyClient) SEND_TO_ANY_CLIENT.add(id);
    }

    /**
     * Construct an empty packet for the given id. Returns {@code null} if no factory is registered
     * (the decoder treats this as a protocol violation and disconnects).
     */
    public static Packet create(int id) {
        Supplier<? extends Packet> f = FACTORIES.get(id);
        return (f == null) ? null : f.get();
    }

    /** True iff the server accepts this packet id from clients. */
    public static boolean isServerReceived(int id) {
        return SERVER_RECEIVED.contains(id);
    }
}

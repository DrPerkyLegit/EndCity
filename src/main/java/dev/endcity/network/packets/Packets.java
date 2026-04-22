package dev.endcity.network.packets;

import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;

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

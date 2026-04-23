package dev.endcity.network.integration;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Minimal scripted LCE client for integration tests. Opens a real TCP socket to a running
 * {@code NetworkManager} on localhost and provides helpers to drive the M1 handshake: consume the
 * small-ID byte, send {@link PreLoginPacket}/{@link LoginPacket}, receive and decode any framed
 * packet, and cleanly close.
 *
 * <p>Everything is blocking on the client side — integration tests are linear scripts, not the
 * place for async gymnastics. The real server is still non-blocking internally.
 */
public final class M1TestClient implements AutoCloseable {

    private final Socket socket;

    public M1TestClient(int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        this.socket.setSoTimeout(5_000);
    }

    public void setReadTimeoutMs(int ms) throws IOException { socket.setSoTimeout(ms); }

    /** Read the server's opening 1-byte small-ID handshake byte. */
    public int readSmallId() throws IOException {
        int b = socket.getInputStream().read();
        if (b < 0) throw new IOException("peer closed before small-ID byte");
        return b;
    }

    /** Send a single raw byte (for e.g. forcing the reject path). */
    public void sendRaw(byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    /** Encode a packet via its own {@code encode()} and push it to the socket. */
    public void send(dev.endcity.network.packets.Packet p) throws IOException {
        ByteBuffer encoded = p.encode();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    /**
     * Read the next framed packet from the socket. Wire format is
     * {@code [4-byte BE length][1 byte id][body]}; we read the length first, then exactly that
     * many bytes for the id + body, then parse.
     */
    public dev.endcity.network.packets.Packet readPacket() throws IOException {
        byte[] header = readFully(4);
        int packetSize = ((header[0] & 0xFF) << 24)
                       | ((header[1] & 0xFF) << 16)
                       | ((header[2] & 0xFF) << 8)
                       |  (header[3] & 0xFF);
        if (packetSize <= 0 || packetSize > 4 * 1024 * 1024) {
            throw new IOException("invalid framed packet size: " + packetSize);
        }

        byte[] frame = readFully(packetSize);
        int id = frame[0] & 0xFF;
        byte[] body = new byte[packetSize - 1];
        System.arraycopy(frame, 1, body, 0, body.length);

        dev.endcity.network.packets.Packet target = switch (id) {
            case 0   -> new KeepAlivePacket();
            case 1   -> new LoginPacket();
            case 2   -> new PreLoginPacket();
            case 255 -> new DisconnectPacket();
            default  -> throw new IOException("unexpected packet id=" + id);
        };
        try {
            target.read(PacketBuffer.wrap(ByteBuffer.wrap(body)));
        } catch (IOException e) {
            throw new IOException("decode(" + target.getClass().getSimpleName() + ") failed: " + e.getMessage(), e);
        }
        return target;
    }

    private <T extends dev.endcity.network.packets.Packet> T decode(T target, int bodyLen) throws IOException {
        byte[] buf = readFully(bodyLen);
        try {
            target.read(PacketBuffer.wrap(ByteBuffer.wrap(buf)));
        } catch (IOException e) {
            throw new IOException("decode(" + target.getClass().getSimpleName() + ") failed: " + e.getMessage(), e);
        }
        return target;
    }

    private LoginPacket decodeLogin() throws IOException {
        return decodeIncrementally(new LoginPacket());
    }

    private PreLoginPacket decodePreLogin() throws IOException {
        return decodeIncrementally(new PreLoginPacket());
    }

    /**
     * Decode a variable-length packet from the socket. Reads one byte at a time into a growing
     * buffer and retries the packet's {@code read()} until it succeeds without underflow. The
     * one-byte granularity avoids over-reading, which matters because over-read bytes would belong
     * to the next packet and be lost.
     *
     * <p>Kept for any callers that pre-date the length-prefixed {@link #readPacket()} path; new
     * code should use {@link #readPacket()} which reads a known-size frame up front.
     */
    private <T extends dev.endcity.network.packets.Packet> T decodeIncrementally(T target) throws IOException {
        byte[] accum = new byte[0];
        while (true) {
            int next = socket.getInputStream().read();
            if (next < 0) throw new java.io.EOFException("peer closed mid-packet after " + accum.length + " bytes");
            byte[] combined = new byte[accum.length + 1];
            System.arraycopy(accum, 0, combined, 0, accum.length);
            combined[accum.length] = (byte) next;
            accum = combined;
            try {
                target.read(PacketBuffer.wrap(ByteBuffer.wrap(accum)));
                return target;
            } catch (java.nio.BufferUnderflowException underflow) {
                // Need more bytes — loop.
            }
        }
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = socket.getInputStream().read(buf, off, n - off);
            if (r < 0) throw new java.io.EOFException("peer closed after " + off + " bytes of " + n);
            off += r;
        }
        return buf;
    }

    /** Build a valid PreLoginPacket with the given login name and net version. */
    public static PreLoginPacket buildPreLogin(String loginKey, int netVersion) {
        PreLoginPacket p = new PreLoginPacket();
        p.netcodeVersion = (short) netVersion;
        p.loginKey = loginKey;
        p.friendsOnlyBits = 0;
        p.ugcPlayersVersion = 0;
        p.playerCount = 1;
        p.playerXuids = new long[] { 0x1122334455667788L };
        byte[] save = "MyWorld\0\0\0\0\0\0\0".getBytes();
        System.arraycopy(save, 0, p.uniqueSaveName, 0, Math.min(save.length, p.uniqueSaveName.length));
        p.serverSettings = 0;
        p.hostIndex = 0;
        p.texturePackId = 0;
        return p;
    }

    /** Build a valid client-side LoginPacket with the given user name. */
    public static LoginPacket buildClientLogin(String userName) {
        LoginPacket p = new LoginPacket();
        p.clientVersion = NetworkConstants.NETWORK_PROTOCOL_VERSION;
        p.userName = userName;
        p.levelTypeName = "";
        p.seed = 0;
        p.gameType = 0;
        p.dimension = 0;
        p.mapHeight = 0;
        p.maxPlayers = 0;
        p.offlineXuid = 0x1122334455667788L;
        p.onlineXuid = 0L;
        p.friendsOnlyUGC = false;
        p.ugcPlayersVersion = 0;
        p.difficulty = 1;
        p.multiplayerInstanceId = 0;
        p.playerIndex = 0;
        p.playerSkinId = 0;
        p.playerCapeId = 0;
        p.isGuest = false;
        p.newSeaLevel = false;
        p.uiGamePrivileges = 0;
        p.xzSize = NetworkConstants.LEVEL_MAX_WIDTH;
        p.hellScale = NetworkConstants.HELL_LEVEL_MAX_SCALE;
        return p;
    }

    @Override
    public void close() throws IOException {
        if (!socket.isClosed()) socket.close();
    }
}

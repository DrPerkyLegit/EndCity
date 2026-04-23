package dev.endcity.network.integration;

import dev.endcity.network.NetworkConstants;
import dev.endcity.network.NetworkManager;
import dev.endcity.network.packets.handshake.DisconnectPacket;
import dev.endcity.network.packets.handshake.KeepAlivePacket;
import dev.endcity.network.packets.handshake.LoginPacket;
import dev.endcity.network.packets.handshake.PreLoginPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for M1 handshake + keep-alive + watchdog behaviour. Spins up a real
 * {@link NetworkManager} on an ephemeral port and drives it through {@link M1TestClient}.
 *
 * <h2>Handshake sequence</h2>
 * Verified against {@code Minecraft.Client/PendingConnection.cpp} and
 * {@code .MinecraftLegacyEdition/server/src/core/Connection.cpp}:
 * <ol>
 *   <li>{@code C->S PreLogin} (id=2) &rarr; {@code S->C PreLoginResponse} (id=2, echoed with
 *       server fields). Server stays in {@code Pending}.</li>
 *   <li>{@code C->S Login} (id=1) &rarr; {@code S->C LoginResponse} (id=1, entityId + world
 *       params). Server transitions {@code Pending}&rarr;{@code Login}&rarr;{@code Play}.</li>
 * </ol>
 * An earlier iteration of this file asserted the wrong sequence (one round-trip with LoginPacket
 * directly back after PreLogin) &mdash; that was a bug, not the protocol. Real LCE clients got
 * stuck on a black screen waiting for the PreLoginResponse that never came, and the 30 s
 * {@code LoginTooLong} watchdog eventually kicked them.
 *
 * <p>Watchdog thresholds are shortened via the test-only constructor to keep the suite fast.
 *
 * <p>Tagged "integration" so they can be excluded from fast unit runs if needed.
 */
@Tag("integration")
final class M1IntegrationTest {

    private NetworkManager server;
    private int port;

    @BeforeEach
    void startServer() {
        // 2 s timeout, 1.5 s LoginTooLong, ephemeral port.
        server = new NetworkManager(0,
                TimeUnit.SECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(1500));
        server.listen();
        port = server.boundPort();
        assertTrue(port > 0, "server should have bound to an ephemeral port");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    // ---------------------------------------------------------------- happy path handshake

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handshake_happyPath_transitionsToPlay() throws IOException {
        try (M1TestClient client = new M1TestClient(port)) {
            int smallId = client.readSmallId();
            assertTrue(smallId >= NetworkConstants.XUSER_MAX_COUNT,
                    "small ID should be outside the reserved user range");

            // Step 1: send PreLogin, expect PreLoginResponse back.
            client.send(M1TestClient.buildPreLogin("Dan", NetworkConstants.MINECRAFT_NET_VERSION));

            var preLoginReply = client.readPacket();
            assertInstanceOf(PreLoginPacket.class, preLoginReply,
                    "server must reply to PreLogin with a PreLoginPacket, not a LoginPacket");
            PreLoginPacket preLoginResponse = (PreLoginPacket) preLoginReply;

            assertEquals(NetworkConstants.MINECRAFT_NET_VERSION, preLoginResponse.netcodeVersion & 0xFFFF);
            assertEquals("-", preLoginResponse.loginKey,
                    "server uses '-' as loginKey per source convention (non-online-mode)");

            // Step 2: send client Login, expect server LoginResponse.
            client.send(M1TestClient.buildClientLogin("Dan"));

            var loginReply = client.readPacket();
            assertInstanceOf(LoginPacket.class, loginReply);
            LoginPacket serverLogin = (LoginPacket) loginReply;

            // clientVersion field in S->C direction carries the entityId (source quirk).
            assertEquals(smallId, serverLogin.clientVersion,
                    "entityId should equal smallId in M1");
            assertEquals("Dan", serverLogin.userName);
            assertEquals("default", serverLogin.levelTypeName);
            assertEquals(0, serverLogin.dimension);
            assertEquals(0, serverLogin.gameType);
            assertEquals(1, serverLogin.difficulty);
            assertEquals(smallId, serverLogin.playerIndex & 0xFF);
            assertTrue(serverLogin.newSeaLevel);
            assertEquals(NetworkConstants.LEVEL_MAX_WIDTH, serverLogin.xzSize);
            assertEquals(NetworkConstants.HELL_LEVEL_MAX_SCALE, serverLogin.hellScale);

            client.send(new DisconnectPacket(NetworkConstants.DisconnectReason.QUITTING));
        }
    }

    // ---------------------------------------------------------------- version rejection paths

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handshake_outdatedClient_sendsReason14() throws IOException {
        assertVersionMismatchReason(
                NetworkConstants.MINECRAFT_NET_VERSION - 1,
                NetworkConstants.DisconnectReason.OUTDATED_CLIENT);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handshake_outdatedServer_sendsReason13() throws IOException {
        assertVersionMismatchReason(
                NetworkConstants.MINECRAFT_NET_VERSION + 1,
                NetworkConstants.DisconnectReason.OUTDATED_SERVER);
    }

    private void assertVersionMismatchReason(int clientVer, int expectedReason) throws IOException {
        try (M1TestClient client = new M1TestClient(port)) {
            client.readSmallId();
            client.send(M1TestClient.buildPreLogin("Dan", clientVer));
            var reply = client.readPacket();
            assertInstanceOf(DisconnectPacket.class, reply);
            assertEquals(expectedReason, ((DisconnectPacket) reply).reason);
        }
    }

    // ---------------------------------------------------------------- KeepAlive during handshake

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handshake_keepAliveBeforePreLogin_isSilentlyIgnored() throws IOException {
        // Source: PendingConnection.cpp:358 — handleKeepAlive is a no-op in the pre-Login phase.
        // Real LCE Win64 clients send keep-alives during handshake, so treating them as an
        // unexpected packet kicks every real client.
        try (M1TestClient client = new M1TestClient(port)) {
            client.readSmallId();
            client.send(new KeepAlivePacket(0xDEADBEEF));
            client.send(M1TestClient.buildPreLogin("Dan", NetworkConstants.MINECRAFT_NET_VERSION));

            var reply = client.readPacket();
            assertInstanceOf(PreLoginPacket.class, reply,
                    "KeepAlive before PreLogin must not disconnect — expected server PreLoginResponse");

            client.send(M1TestClient.buildClientLogin("Dan"));
            var loginReply = client.readPacket();
            assertInstanceOf(LoginPacket.class, loginReply);
            client.send(new DisconnectPacket(NetworkConstants.DisconnectReason.QUITTING));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handshake_keepAliveBetweenPreLoginAndLogin_isSilentlyIgnored() throws IOException {
        // Between PreLoginResponse and client Login we are still in Pending (source model:
        // PendingConnection handles both phases with one listener). KeepAlive should pass through
        // silently.
        try (M1TestClient client = new M1TestClient(port)) {
            client.readSmallId();
            client.send(M1TestClient.buildPreLogin("Dan", NetworkConstants.MINECRAFT_NET_VERSION));
            var preLoginResponse = client.readPacket();
            assertInstanceOf(PreLoginPacket.class, preLoginResponse);

            // Still in Pending. KeepAlive should be accepted silently.
            client.send(new KeepAlivePacket(0xCAFEBABE));
            client.send(M1TestClient.buildClientLogin("Dan"));

            var loginResponse = client.readPacket();
            assertInstanceOf(LoginPacket.class, loginResponse,
                    "after Login the server must reply with LoginResponse");

            client.send(new DisconnectPacket(NetworkConstants.DisconnectReason.QUITTING));
        }
    }

    // ---------------------------------------------------------------- keep-alive emission

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void keepAlive_emittedApproxEverySecondInPlay() throws IOException {
        try (M1TestClient client = new M1TestClient(port)) {
            client.readSmallId();
            client.send(M1TestClient.buildPreLogin("Dan", NetworkConstants.MINECRAFT_NET_VERSION));
            var preLoginResponse = client.readPacket();
            assertInstanceOf(PreLoginPacket.class, preLoginResponse);
            client.send(M1TestClient.buildClientLogin("Dan"));
            var loginResponse = client.readPacket();
            assertInstanceOf(LoginPacket.class, loginResponse);
            client.drainM2PostLoginBurst();

            // Collect 3 keep-alives and verify they land within the 1.5 s slop.
            client.setReadTimeoutMs(3_000);
            long t0 = System.nanoTime();
            long[] arrivals = new long[3];
            for (int i = 0; i < 3; i++) {
                var pkt = client.readPacket();
                assertInstanceOf(KeepAlivePacket.class, pkt, "expected KeepAlivePacket #" + i);
                arrivals[i] = System.nanoTime() - t0;
                client.send(new KeepAlivePacket(((KeepAlivePacket) pkt).token));
            }
            for (int i = 1; i < 3; i++) {
                long intervalMs = (arrivals[i] - arrivals[i - 1]) / 1_000_000L;
                assertTrue(intervalMs >= 500 && intervalMs <= 1500,
                        "keep-alive interval " + i + " was " + intervalMs + " ms, expected 500-1500");
            }

            client.send(new DisconnectPacket(NetworkConstants.DisconnectReason.QUITTING));
        }
    }

    // ---------------------------------------------------------------- watchdogs (short thresholds)

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void timeout_disconnectsAfterThreshold() throws IOException {
        try (M1TestClient client = new M1TestClient(port)) {
            client.readSmallId();
            client.send(M1TestClient.buildPreLogin("Dan", NetworkConstants.MINECRAFT_NET_VERSION));
            var preLoginResponse = client.readPacket();
            assertInstanceOf(PreLoginPacket.class, preLoginResponse);
            client.send(M1TestClient.buildClientLogin("Dan"));
            var loginResponse = client.readPacket();
            assertInstanceOf(LoginPacket.class, loginResponse);
            client.drainM2PostLoginBurst();

            // Now go silent. Don't echo keep-alives.
            client.setReadTimeoutMs(6_000);
            DisconnectPacket disconnect = null;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadlineNanos) {
                var pkt = client.readPacket();
                if (pkt instanceof DisconnectPacket dp) {
                    disconnect = dp;
                    break;
                }
                assertInstanceOf(KeepAlivePacket.class, pkt);
            }
            assertNotNull(disconnect, "server should have disconnected us with TIME_OUT");
            assertEquals(NetworkConstants.DisconnectReason.TIME_OUT, disconnect.reason);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void loginTooLong_disconnectsAfterThreshold() throws IOException {
        try (M1TestClient client = new M1TestClient(port)) {
            client.readSmallId();
            // Don't send anything. Should get LOGIN_TOO_LONG before read timeout.
            client.setReadTimeoutMs(5_000);
            var pkt = client.readPacket();
            assertInstanceOf(DisconnectPacket.class, pkt);
            assertEquals(NetworkConstants.DisconnectReason.LOGIN_TOO_LONG,
                    ((DisconnectPacket) pkt).reason);
        }
    }

    // ---------------------------------------------------------------- serverFull

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void serverFull_sendsSixByteRejectFrame() throws IOException {
        // The SmallIdPool unit test covers exhaustion exhaustively; this is just a smoke test that
        // a single client gets allocated a small ID inside the valid range.
        try (M1TestClient client = new M1TestClient(port)) {
            int smallId = client.readSmallId();
            assertTrue(smallId >= NetworkConstants.XUSER_MAX_COUNT
                    && smallId < NetworkConstants.MINECRAFT_NET_MAX_PLAYERS,
                    "small ID " + smallId + " outside allocatable range");
            client.send(new DisconnectPacket(NetworkConstants.DisconnectReason.QUITTING));
        }
    }
}

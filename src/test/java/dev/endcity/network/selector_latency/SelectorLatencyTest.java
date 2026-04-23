package dev.endcity.network.selector_latency;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical proof that {@code select(1000)} on a non-blocking channel delivers inbound bytes with
 * sub-millisecond latency — not blocked by the 1000 ms timeout.
 *
 * <p>Measures wall-clock from "client sends byte" to "server select() returns with OP_READ ready".
 * If DrPerky is right and select blocks for the full timeout, we'll see ~1000 ms.
 * If I'm right and select returns on OS readiness event, we'll see microseconds.
 */
final class SelectorLatencyTest {

    @Test
    void selectWithTimeout_wakesImmediatelyOnInboundByte() throws Exception {
        // Set up: server binds ephemeral port, client connects, both non-blocking.
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             Selector selector = Selector.open()) {

            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            // Accept on background thread so the main thread can drive the client.
            CountDownLatch serverReady = new CountDownLatch(1);
            CountDownLatch byteArrived = new CountDownLatch(1);
            long[] clientSendNanos = new long[1];
            long[] serverReadNanos = new long[1];

            Thread serverThread = new Thread(() -> {
                try {
                    SocketChannel accepted = null;
                    while (accepted == null) {
                        selector.select(1000);
                        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                        while (it.hasNext()) {
                            SelectionKey key = it.next();
                            it.remove();
                            if (key.isAcceptable()) {
                                accepted = serverChannel.accept();
                                accepted.configureBlocking(false);
                                accepted.register(selector, SelectionKey.OP_READ);
                            }
                        }
                    }
                    serverReady.countDown();

                    // Now block on select(1000) waiting for inbound bytes. If it really blocks for the
                    // full 1 s regardless of byte arrival, our measured latency will be ~1000 ms.
                    // If it returns on OS readiness, it'll be microseconds.
                    ByteBuffer buf = ByteBuffer.allocate(16);
                    while (byteArrived.getCount() > 0) {
                        selector.select(1000);
                        long wakeTime = System.nanoTime();
                        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                        while (it.hasNext()) {
                            SelectionKey key = it.next();
                            it.remove();
                            if (key.isReadable()) {
                                int n = ((SocketChannel) key.channel()).read(buf);
                                if (n > 0) {
                                    serverReadNanos[0] = wakeTime;
                                    byteArrived.countDown();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "test-server");
            serverThread.start();

            // Client: connect, wait for server to be ready, then send ONE byte and record the
            // exact nanosecond we called write().
            try (SocketChannel client = SocketChannel.open()) {
                client.configureBlocking(true);  // blocking on client side for simplicity
                client.connect(new InetSocketAddress("127.0.0.1", port));
                assertTrue(serverReady.await(2, TimeUnit.SECONDS), "server didn't accept in time");

                // Small pause so we're definitely mid-select() on the server side.
                Thread.sleep(100);

                clientSendNanos[0] = System.nanoTime();
                ByteBuffer oneByte = ByteBuffer.wrap(new byte[] { 0x42 });
                client.write(oneByte);

                assertTrue(byteArrived.await(2, TimeUnit.SECONDS),
                        "server select() never returned — this would mean it blocked the full timeout");
            }

            serverThread.join(2000);

            long latencyNanos = serverReadNanos[0] - clientSendNanos[0];
            long latencyMicros = latencyNanos / 1_000L;
            long latencyMillis = latencyNanos / 1_000_000L;

            System.out.printf("Byte send → selector wake latency: %d ns = %d µs = %d ms%n",
                    latencyNanos, latencyMicros, latencyMillis);

            // The core assertion: select(1000) did NOT wait anywhere near 1 s.
            // On localhost this should be well under 10 ms, usually under 1 ms.
            // If it's >100 ms something is fundamentally wrong (or we're on a pathologically
            // slow CI runner).
            assertTrue(latencyMillis < 100,
                    "select(1000) took " + latencyMillis + "ms to wake after a byte arrived — "
                    + "if this exceeds the timeout, select really IS blocking I/O. "
                    + "If it's << 1000 then select returns on readiness, not on timeout.");
        }
    }
}

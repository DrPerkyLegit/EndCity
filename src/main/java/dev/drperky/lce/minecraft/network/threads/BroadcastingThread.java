package dev.drperky.lce.minecraft.network.threads;

import dev.drperky.lce.minecraft.network.NetworkConstants;
import dev.drperky.lce.minecraft.network.NetworkManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accept-only thread. Binds the listening socket on the configured port (default
 * {@link NetworkConstants#WIN64_NET_DEFAULT_PORT}), accepts incoming TCP connections, sets
 * {@code TCP_NODELAY} (matching the source — see {@code WinsockNetLayer.cpp:824}), and delegates each
 * accepted channel to {@link NetworkManager#handleIncomingConnection}.
 *
 * <p>This thread must <em>never</em> do packet I/O itself — that's the per-connection
 * {@link ConnectionThread}'s job. See {@code PLAN.md} §2 rule 7.
 */
public final class BroadcastingThread extends Thread {

    private static final Logger LOGGER = Logger.getLogger(BroadcastingThread.class.getName());

    private final NetworkManager networkManager;
    private final int requestedPort;
    private final CountDownLatch boundLatch = new CountDownLatch(1);
    private volatile int boundPort = -1;

    public BroadcastingThread(NetworkManager networkManager) {
        this(networkManager, NetworkConstants.WIN64_NET_DEFAULT_PORT);
    }

    /** Test-only / advanced: use port 0 for an ephemeral port, then query {@link #boundPort()}. */
    public BroadcastingThread(NetworkManager networkManager, int port) {
        super("BroadcastingThread");
        this.networkManager = networkManager;
        this.requestedPort = port;
        setDaemon(false);
    }

    /** @return the port the socket actually bound to, or -1 if it hasn't bound yet. */
    public int boundPort() { return boundPort; }

    /** Block the caller until the socket is bound or the thread exits. */
    public void awaitBound() throws InterruptedException { boundLatch.await(); }

    @Override
    public void run() {
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             Selector selector = Selector.open()) {

            serverChannel.configureBlocking(false);
            // Allow quick rebinds after a crash/restart. Without this, the OS holds the port in
            // TIME_WAIT for up to 60s after the old process dies and the next `gradlew run` fails
            // with BindException. This does NOT allow two active listeners to share the port.
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(requestedPort));
            this.boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            boundLatch.countDown();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            LOGGER.log(Level.INFO, "listening on port {0}", boundPort);

            while (!Thread.currentThread().isInterrupted()) {
                selector.select();

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid() || !key.isAcceptable()) continue;

                    SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
                    if (clientChannel == null) continue;

                    try {
                        clientChannel.configureBlocking(false);
                        clientChannel.socket().setTcpNoDelay(true);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "failed to configure accepted socket, closing: " + e.getMessage());
                        try { clientChannel.close(); } catch (IOException ignored) {}
                        continue;
                    }

                    networkManager.handleIncomingConnection(clientChannel);
                }
            }
        } catch (IOException e) {
            boundLatch.countDown();
            LOGGER.log(Level.SEVERE, "accept loop died", e);
            throw new RuntimeException(e);
        }
    }
}

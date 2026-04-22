package dev.drperky.lce.minecraft.network.threads;

import dev.drperky.lce.minecraft.network.NetworkManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;

public class BroadcastingThread extends Thread {

    private final NetworkManager _networkManager;

    public BroadcastingThread(NetworkManager _networkManager) {
        this._networkManager = _networkManager;
    }

    public void run() {
        try {
            ServerSocketChannel proxySocket = ServerSocketChannel.open();
            proxySocket.configureBlocking(false);
            proxySocket.bind(new InetSocketAddress(25565));

            Selector selector = Selector.open();
            proxySocket.register(selector, SelectionKey.OP_ACCEPT);

            while (!Thread.currentThread().isInterrupted()) {
                selector.select();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (key.isAcceptable()) {
                        SocketChannel clientChannel = ((ServerSocketChannel)key.channel()).accept();
                        if (clientChannel != null) {
                            clientChannel.configureBlocking(false);
                            clientChannel.socket().setTcpNoDelay(true);

                            _networkManager.handleIncomingConnection(clientChannel);
                        }
                    }
                }

                sleep(1);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

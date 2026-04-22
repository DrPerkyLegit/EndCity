package dev.drperky.lce.minecraft.network;

import dev.drperky.lce.minecraft.network.threads.BroadcastingThread;
import dev.drperky.lce.minecraft.network.threads.ConnectionThread;
import dev.drperky.lce.minecraft.network.utils.SmallIdPool;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

public class NetworkManager {
    private final BroadcastingThread _broadcastThread;
    private final List<ConnectionThread> _connectionThreads;

    public final SmallIdPool _smallidPool = new SmallIdPool();

    public NetworkManager() {
        this._broadcastThread = new BroadcastingThread(this);

        this._connectionThreads = new ArrayList<>();

        int connectionThreads = 4;
        for (int i = 0; i < connectionThreads; i++) {
            _connectionThreads.add(new ConnectionThread(this));
        }
    }

    public void listen() {
        for (ConnectionThread connectionThread : _connectionThreads) {
            connectionThread.start();
        }

        _broadcastThread.start();
    }

    public void handleIncomingConnection(SocketChannel connection) {
        try {
            int allocatedSmallId = _smallidPool.allocate();
            {
                ByteBuffer clientSmallId = ByteBuffer.allocate(1);
                clientSmallId.put((byte)allocatedSmallId);

                connection.write(clientSmallId);
            }



        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

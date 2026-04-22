package dev.drperky.lce.minecraft.network.threads;

import dev.drperky.lce.minecraft.network.NetworkManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class ConnectionThread extends Thread {
    private final NetworkManager _networkManager;

    public ConnectionThread(NetworkManager _networkManager) {
        this._networkManager = _networkManager;
    }

    public void run() {
        try {

            while (!Thread.currentThread().isInterrupted()) {


                sleep(1);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

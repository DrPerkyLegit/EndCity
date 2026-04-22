package dev.drperky.lce.minecraft.server;

import dev.drperky.lce.minecraft.network.NetworkManager;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class MinecraftServer {

    private static final Logger LOGGER = Logger.getLogger(MinecraftServer.class.getName());

    private final NetworkManager networkManager;
    private volatile boolean running = true;

    public MinecraftServer() {
        this.networkManager = new NetworkManager();
    }

    public void start() {
        networkManager.listen();
        LOGGER.log(Level.INFO, "EndCity up; no game tick loop yet (M0 transport-only)");

        // M7 wires in a real 20 Hz tick loop. Until then, keep the main thread alive without burning
        // CPU so the network threads can service clients. Daemon=false on the accept + connection
        // threads keeps the JVM alive, but holding the main thread here matches the eventual loop.
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
            tick();
        }
    }

    private void tick() {
        // Placeholder. World/entity ticking lands in M7.
    }

    public void stop() {
        running = false;
    }
}

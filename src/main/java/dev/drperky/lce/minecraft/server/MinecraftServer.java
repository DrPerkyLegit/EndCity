package dev.drperky.lce.minecraft.server;

import dev.drperky.lce.minecraft.network.NetworkManager;
import dev.drperky.lce.minecraft.network.NetworkThread;

public class MinecraftServer {
    private final NetworkManager _networkManager;

    private boolean isRunning = true;
    public MinecraftServer() {
        this._networkManager = new NetworkManager();
    }

    public void start() {
        this._networkManager.listen();

        while (isRunning) {
            //this.tick();
        }
    }

    private void tick() {

    }
}

package dev.drperky;
//current net version is 560

import dev.drperky.lce.minecraft.server.MinecraftServer;

public class Start {
    public static void main(String[] args) {
        System.out.println("Server Started");
        MinecraftServer server = new MinecraftServer();

        server.start();
    }
}
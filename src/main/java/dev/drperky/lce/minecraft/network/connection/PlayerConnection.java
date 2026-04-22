package dev.drperky.lce.minecraft.network.connection;

import java.nio.channels.SocketChannel;

public class PlayerConnection {
    SocketChannel connection;
    int assignedSmallID;
    ConnectionState state;



    PlayerConnection(SocketChannel connection, int smallid) {
        this.connection = connection;
        this.assignedSmallID = smallid;

        this.state = ConnectionState.Pending;
    }
}

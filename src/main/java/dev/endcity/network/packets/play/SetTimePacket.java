package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 4, S&rarr;C only. Tells the client the current world time.
 *
 * <p>Source: {@code Minecraft.World/SetTimePacket.cpp::read/write} &mdash; two {@code Long}s.
 *
 * <p>Wire body: {@code [Long gameTime][Long dayTime]}. Both big-endian. 16 bytes total (matches
 * {@code getEstimatedSize()} in the source).
 *
 * <p>Semantics: {@code gameTime} is the absolute tick count since world creation;
 * {@code dayTime} is the tick count within the 24000-tick day/night cycle. The source has a
 * commented-out code path that negates {@code dayTime} to freeze the clock (the daylight-cycle
 * game rule in host options supersedes it); we do not reimplement that.
 *
 * <p>This packet is server-only: it is never received from clients. {@link #read(PacketBuffer)}
 * is still implemented so we can write symmetric round-trip tests, but {@link #handle} throws
 * {@link UnsupportedOperationException} if something ever tries to dispatch it inbound.
 */
public final class SetTimePacket extends Packet {

    public long gameTime;
    public long dayTime;

    public SetTimePacket() {}

    public SetTimePacket(long gameTime, long dayTime) {
        this.gameTime = gameTime;
        this.dayTime = dayTime;
    }

    @Override
    public int getId() { return 4; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        gameTime = buf.readLong();
        dayTime = buf.readLong();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeLong(gameTime);
        buf.writeLong(dayTime);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException("SetTimePacket is server-only; never dispatched inbound");
    }
}

package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 8, S&rarr;C only. Syncs the player's HUD stats.
 *
 * <p>Source: {@code Minecraft.World/SetHealthPacket.cpp::read/write}:
 * <pre>
 *   writeFloat(health);     // 4 bytes
 *   writeShort(food);       // 2 bytes  &mdash; NOT a byte. Wider-than-needed but that's what the source does.
 *   writeFloat(saturation); // 4 bytes
 *   writeByte(damageSource);// 1 byte   &mdash; ETelemetryChallenges enum ordinal
 * </pre>
 * Total 11 bytes body, matching {@code getEstimatedSize() == 11}.
 *
 * <p>Typical values for a full-health full-food player: {@code health=20.0, food=20,
 * saturation=5.0, damageSource=0}.
 *
 * <p>{@code damageSource} is the reason-for-change marker used by 4J's telemetry hooks (kept at 0
 * for unsolicited health updates like spawn and respawn). We expose it as a plain byte since the
 * enum isn't interesting for M2.
 *
 * <p>Server-only. See class docs on {@link SetTimePacket} for why {@link #handle} throws.
 */
public final class SetHealthPacket extends Packet {

    public static final byte DAMAGE_SOURCE_UNKNOWN = 0;

    public float health;
    public short food;
    public float saturation;
    public byte damageSource;

    public SetHealthPacket() {}

    public SetHealthPacket(float health, short food, float saturation, byte damageSource) {
        this.health = health;
        this.food = food;
        this.saturation = saturation;
        this.damageSource = damageSource;
    }

    @Override
    public int getId() { return 8; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        health = buf.readFloat();
        food = buf.readShort();
        saturation = buf.readFloat();
        damageSource = buf.readByte();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeFloat(health);
        buf.writeShort(food);
        buf.writeFloat(saturation);
        buf.writeByte(damageSource);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException(
                "SetHealthPacket is server-only; never dispatched inbound");
    }
}

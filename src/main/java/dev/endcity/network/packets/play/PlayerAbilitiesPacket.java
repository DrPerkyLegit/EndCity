package dev.endcity.network.packets.play;

import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;

/**
 * Packet id 202, S&rarr;C only. Syncs the player's ability flags and movement speeds to the client.
 *
 * <p>Source: {@code Minecraft.World/PlayerAbilitiesPacket.cpp::read/write}:
 * <pre>
 *   writeByte(bitfield);         // INVULNERABLE|FLYING|CAN_FLY|INSTABUILD
 *   writeFloat(flyingSpeed);
 *   writeFloat(walkingSpeed);
 * </pre>
 * 9 bytes body.
 *
 * <p>Flag bits (from {@code PlayerAbilitiesPacket.h}):
 * <ul>
 *   <li>{@link #FLAG_INVULNERABLE} = 0x01</li>
 *   <li>{@link #FLAG_FLYING}       = 0x02</li>
 *   <li>{@link #FLAG_CAN_FLY}      = 0x04</li>
 *   <li>{@link #FLAG_INSTABUILD}   = 0x08</li>
 * </ul>
 *
 * <p>{@code walkingSpeed} and {@code flyingSpeed} are normalized multipliers; vanilla values are
 * {@code 0.1f} and {@code 0.05f} respectively for a standard survival-mode player.
 *
 * <p>Server-only. See class docs on {@link SetTimePacket} for why {@link #handle} throws.
 */
public final class PlayerAbilitiesPacket extends Packet {

    public static final byte FLAG_INVULNERABLE = 0x01;
    public static final byte FLAG_FLYING       = 0x02;
    public static final byte FLAG_CAN_FLY      = 0x04;
    public static final byte FLAG_INSTABUILD   = 0x08;

    /** Default movement speed in ability-units (matches vanilla survival). */
    public static final float DEFAULT_WALKING_SPEED = 0.1f;
    /** Default flying speed in ability-units. */
    public static final float DEFAULT_FLYING_SPEED  = 0.05f;

    public byte flags;
    public float flyingSpeed;
    public float walkingSpeed;

    public PlayerAbilitiesPacket() {}

    public PlayerAbilitiesPacket(byte flags, float flyingSpeed, float walkingSpeed) {
        this.flags = flags;
        this.flyingSpeed = flyingSpeed;
        this.walkingSpeed = walkingSpeed;
    }

    public boolean isInvulnerable() { return (flags & FLAG_INVULNERABLE) != 0; }
    public boolean isFlying()       { return (flags & FLAG_FLYING)       != 0; }
    public boolean canFly()         { return (flags & FLAG_CAN_FLY)      != 0; }
    public boolean canInstabuild()  { return (flags & FLAG_INSTABUILD)   != 0; }

    public void setInvulnerable(boolean v) { flags = setFlag(FLAG_INVULNERABLE, v); }
    public void setFlying(boolean v)       { flags = setFlag(FLAG_FLYING,       v); }
    public void setCanFly(boolean v)       { flags = setFlag(FLAG_CAN_FLY,      v); }
    public void setInstabuild(boolean v)   { flags = setFlag(FLAG_INSTABUILD,   v); }

    private byte setFlag(byte bit, boolean enable) {
        return (byte) (enable ? (flags | bit) : (flags & ~bit));
    }

    @Override
    public int getId() { return 202; }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        flags = buf.readByte();
        flyingSpeed = buf.readFloat();
        walkingSpeed = buf.readFloat();
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        buf.writeByte(flags);
        buf.writeFloat(flyingSpeed);
        buf.writeFloat(walkingSpeed);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException(
                "PlayerAbilitiesPacket is server-only; never dispatched inbound");
    }
}

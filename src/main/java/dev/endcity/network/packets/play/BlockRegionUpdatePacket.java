package dev.endcity.network.packets.play;

import dev.endcity.network.compression.RleZlibCompressor;
import dev.endcity.network.packets.Packet;
import dev.endcity.network.packets.PacketListener;
import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Packet id 51, S&rarr;C only. Bulk block-region payload used for initial chunk streaming.
 *
 * <p>Source: {@code Minecraft.World/BlockRegionUpdatePacket.cpp::write}. The byte buffer carried
 * by this Java class is already compressed with 4J's RLE+zlib transform.
 */
public final class BlockRegionUpdatePacket extends Packet {

    public static final int FLAG_FULL_CHUNK = 0x01;
    public static final int FLAG_ZERO_HEIGHT = 0x02;

    public byte chunkFlags;
    public int x;
    public short y;
    public int z;
    public int xs;
    public int ys;
    public int zs;
    public int levelIdx;
    public byte[] compressed = new byte[0];

    public BlockRegionUpdatePacket() {}

    public BlockRegionUpdatePacket(int x, int y, int z, int xs, int ys, int zs,
                                   int levelIdx, boolean fullChunk, byte[] rawData) {
        if (xs < 1 || xs > 256 || ys < 1 || ys > 256 || zs < 1 || zs > 256) {
            throw new IllegalArgumentException("region dimensions must be in [1, 256]");
        }
        if (levelIdx < 0 || levelIdx > 3) {
            throw new IllegalArgumentException("levelIdx must fit in 2 bits");
        }
        this.x = x;
        this.y = (short) y;
        this.z = z;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.levelIdx = levelIdx;
        this.chunkFlags = (byte) (fullChunk ? FLAG_FULL_CHUNK : 0);
        Objects.requireNonNull(rawData, "rawData");
        byte[] rawCopy = Arrays.copyOf(rawData, rawData.length);
        this.compressed = RleZlibCompressor.compress(rawCopy);
    }

    @Override
    public int getId() { return 51; }

    @Override
    protected int estimatedBodySize() {
        return 18 + compressed.length;
    }

    @Override
    public void read(PacketBuffer buf) throws IOException {
        chunkFlags = buf.readByte();
        x = buf.readInt();
        y = buf.readShort();
        z = buf.readInt();
        xs = (buf.readByte() & 0xFF) + 1;
        ys = (buf.readByte() & 0xFF) + 1;
        zs = (buf.readByte() & 0xFF) + 1;
        if ((chunkFlags & FLAG_ZERO_HEIGHT) != 0) {
            ys = 0;
        }
        int sizeAndLevel = buf.readInt();
        levelIdx = (sizeAndLevel >>> 30) & 0x3;
        int size = sizeAndLevel & 0x3FFFFFFF;
        compressed = new byte[size];
        buf.readBytes(compressed, 0, size);
    }

    @Override
    public void write(PacketBuffer buf) throws IOException {
        byte flags = chunkFlags;
        if (ys == 0) {
            flags |= FLAG_ZERO_HEIGHT;
        }

        buf.writeByte(flags);
        buf.writeInt(x);
        buf.writeShort(y);
        buf.writeInt(z);
        buf.writeByte(xs - 1);
        buf.writeByte(ys - 1);
        buf.writeByte(zs - 1);
        buf.writeInt((levelIdx << 30) | compressed.length);
        buf.writeBytes(compressed);
    }

    @Override
    public void handle(PacketListener listener) throws IOException {
        throw new UnsupportedOperationException(
                "BlockRegionUpdatePacket is server-only; never dispatched inbound");
    }
}

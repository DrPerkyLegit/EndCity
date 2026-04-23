package dev.endcity.world.chunk;

import java.util.Arrays;

/**
 * Hardcoded flat-world generator for M2.3. The output is already in the reordered raw format that
 * {@code BlockRegionUpdatePacket} compresses:
 *
 * <pre>
 * blocks[16*ys*16] + data[half] + blockLight[half] + skyLight[half] + biomes[256]
 * </pre>
 */
public final class FlatChunkGenerator {

    public static final int CHUNK_SIZE = 16;
    public static final int CHUNK_HEIGHT = 256;
    public static final int BIOME_PLAINS = 1;

    private static final int BLOCK_AIR = 0;
    private static final int BLOCK_GRASS = 2;
    private static final int BLOCK_DIRT = 3;
    private static final int BLOCK_BEDROCK = 7;

    private FlatChunkGenerator() {}

    public static FlatChunk generate(int chunkX, int chunkZ) {
        int tileCount = CHUNK_SIZE * CHUNK_HEIGHT * CHUNK_SIZE;
        int halfTile = tileCount / 2;
        int biomeLen = CHUNK_SIZE * CHUNK_SIZE;
        byte[] raw = new byte[tileCount + 3 * halfTile + biomeLen];

        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int y = 0; y < CHUNK_HEIGHT; y++) {
                    int slot = blockSlot(x, y, z);
                    if (y == 0) {
                        raw[slot] = BLOCK_BEDROCK;
                    } else if (y < 4) {
                        raw[slot] = BLOCK_DIRT;
                    } else if (y == 4) {
                        raw[slot] = BLOCK_GRASS;
                    } else {
                        raw[slot] = BLOCK_AIR;
                    }
                }
            }
        }

        int skyLightOffset = tileCount + 2 * halfTile;
        Arrays.fill(raw, skyLightOffset, skyLightOffset + halfTile, (byte) 0xFF);

        int biomeOffset = tileCount + 3 * halfTile;
        Arrays.fill(raw, biomeOffset, biomeOffset + biomeLen, (byte) BIOME_PLAINS);

        return new FlatChunk(chunkX, chunkZ, CHUNK_HEIGHT, raw);
    }

    public static int blockSlot(int x, int y, int z) {
        return y * CHUNK_SIZE * CHUNK_SIZE + z * CHUNK_SIZE + x;
    }

    public static int expectedRawSize() {
        int tileCount = CHUNK_SIZE * CHUNK_HEIGHT * CHUNK_SIZE;
        return tileCount + 3 * (tileCount / 2) + CHUNK_SIZE * CHUNK_SIZE;
    }
}

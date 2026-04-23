package dev.endcity.world.chunk;

/**
 * Minimal generated chunk payload for M2.3 login streaming.
 *
 * @param chunkX chunk coordinate X
 * @param chunkZ chunk coordinate Z
 * @param ys vertical span encoded in the BlockRegionUpdate packet
 * @param rawData reordered block/data/light/biome bytes before RLE+zlib compression
 */
public record FlatChunk(int chunkX, int chunkZ, int ys, byte[] rawData) {}

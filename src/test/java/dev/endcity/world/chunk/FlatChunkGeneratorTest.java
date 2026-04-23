package dev.endcity.world.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FlatChunkGeneratorTest {

    @Test
    void generate_buildsExpectedRawLayout() {
        FlatChunk chunk = FlatChunkGenerator.generate(2, -3);

        assertEquals(2, chunk.chunkX());
        assertEquals(-3, chunk.chunkZ());
        assertEquals(256, chunk.ys());
        assertEquals(FlatChunkGenerator.expectedRawSize(), chunk.rawData().length);

        assertEquals(7, chunk.rawData()[FlatChunkGenerator.blockSlot(0, 0, 0)] & 0xFF);
        assertEquals(3, chunk.rawData()[FlatChunkGenerator.blockSlot(0, 1, 0)] & 0xFF);
        assertEquals(3, chunk.rawData()[FlatChunkGenerator.blockSlot(15, 3, 15)] & 0xFF);
        assertEquals(2, chunk.rawData()[FlatChunkGenerator.blockSlot(8, 4, 8)] & 0xFF);
        assertEquals(0, chunk.rawData()[FlatChunkGenerator.blockSlot(8, 5, 8)] & 0xFF);

        int tileCount = 16 * 256 * 16;
        int halfTile = tileCount / 2;
        int skyLightOffset = tileCount + 2 * halfTile;
        int biomeOffset = tileCount + 3 * halfTile;

        assertEquals(0xFF, chunk.rawData()[skyLightOffset] & 0xFF);
        assertEquals(1, chunk.rawData()[biomeOffset] & 0xFF);
        assertEquals(1, chunk.rawData()[biomeOffset + 255] & 0xFF);
    }
}

package dev.drperky.lce.minecraft.network.utils;

import dev.drperky.lce.minecraft.network.NetworkConstants;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SmallIdPoolTest {

    private static final int CAPACITY =
            NetworkConstants.MINECRAFT_NET_MAX_PLAYERS - NetworkConstants.XUSER_MAX_COUNT;

    @Test
    void freshPool_reportsFullCapacity() {
        SmallIdPool pool = new SmallIdPool();
        assertEquals(CAPACITY, pool.capacity());
        assertEquals(CAPACITY, pool.available());
    }

    @Test
    void firstAllocation_returnsXUserMaxCount() {
        SmallIdPool pool = new SmallIdPool();
        assertEquals(NetworkConstants.XUSER_MAX_COUNT, pool.allocate(),
                "first allocation must start at XUSER_MAX_COUNT (4) to match WinsockNetLayer.cpp s_nextSmallId init");
    }

    @Test
    void sequentialAllocations_useAscendingIds_onFreshPool() {
        SmallIdPool pool = new SmallIdPool();
        for (int expected = NetworkConstants.XUSER_MAX_COUNT;
             expected < NetworkConstants.MINECRAFT_NET_MAX_PLAYERS;
             expected++) {
            assertEquals(expected, pool.allocate());
        }
    }

    @Test
    void allocate_returnsDistinctIdsInValidRange() {
        SmallIdPool pool = new SmallIdPool();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < CAPACITY; i++) {
            int id = pool.allocate();
            assertTrue(id >= NetworkConstants.XUSER_MAX_COUNT
                            && id < NetworkConstants.MINECRAFT_NET_MAX_PLAYERS,
                    "id " + id + " out of expected range");
            assertTrue(seen.add(id), "duplicate id handed out: " + id);
        }
        assertEquals(CAPACITY, seen.size());
    }

    @Test
    void allocate_afterExhaustion_returnsNoIdSentinel() {
        SmallIdPool pool = new SmallIdPool();
        for (int i = 0; i < CAPACITY; i++) pool.allocate();
        assertEquals(0, pool.available());
        // M0 verification item: the 253rd allocation (1-indexed: 252+1) must signal exhaustion
        // so handleIncomingConnection can send eDisconnect_ServerFull.
        assertEquals(SmallIdPool.NO_ID, pool.allocate(), "exhausted pool must return NO_ID");
    }

    @Test
    void freedId_isReusedOnNextAllocation_lifo() {
        SmallIdPool pool = new SmallIdPool();
        // Drain 252 IDs.
        int[] allocated = new int[CAPACITY];
        for (int i = 0; i < CAPACITY; i++) allocated[i] = pool.allocate();
        assertEquals(SmallIdPool.NO_ID, pool.allocate(), "pool must be exhausted after draining capacity");

        // Free an ID from the middle.
        int freed = allocated[100];
        pool.free(freed);
        assertEquals(1, pool.available());

        // Next allocation must be the exact ID we just freed (LIFO recycle).
        assertEquals(freed, pool.allocate(), "LIFO recycle: freed ID must be first out");

        // And the pool is exhausted again.
        assertEquals(SmallIdPool.NO_ID, pool.allocate());
    }

    @Test
    void freedIds_areReusedInReverseOrderOfRelease() {
        SmallIdPool pool = new SmallIdPool();
        int a = pool.allocate();
        int b = pool.allocate();
        int c = pool.allocate();

        pool.free(a);
        pool.free(b);
        pool.free(c);

        // LIFO: last freed is first out.
        assertEquals(c, pool.allocate());
        assertEquals(b, pool.allocate());
        assertEquals(a, pool.allocate());
    }

    @Test
    void free_rejectsOutOfRangeIds() {
        SmallIdPool pool = new SmallIdPool();
        assertThrows(IllegalArgumentException.class, () -> pool.free(0));
        assertThrows(IllegalArgumentException.class, () -> pool.free(3));
        assertThrows(IllegalArgumentException.class, () -> pool.free(256));
        assertThrows(IllegalArgumentException.class, () -> pool.free(-1));
    }

    @Test
    void free_rejectsDoubleFree() {
        SmallIdPool pool = new SmallIdPool();
        int id = pool.allocate();
        pool.free(id);
        assertThrows(IllegalStateException.class, () -> pool.free(id),
                "double-free indicates a caller bug and must be loud");
    }
}

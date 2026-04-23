package dev.endcity.network.utils;

import dev.endcity.network.NetworkConstants;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * LIFO small-ID allocator mirroring the C++ source's small-ID handling in
 * {@code WinsockNetLayer::AcceptThreadProc} (see {@code WinsockNetLayer.cpp:878-890}).
 *
 * <p>IDs 0..{@link NetworkConstants#XUSER_MAX_COUNT XUSER_MAX_COUNT}-1 are reserved for local split-screen
 * users and never handed out. Remote clients get IDs in the range
 * {@code [XUSER_MAX_COUNT, MINECRAFT_NET_MAX_PLAYERS)} — i.e. 4..255 inclusive, giving
 * {@code MINECRAFT_NET_MAX_PLAYERS - XUSER_MAX_COUNT = 252} concurrent remote connections.
 *
 * <p>Thread-safe: all public operations synchronize on the backing deque.
 */
public final class SmallIdPool {

    /** Sentinel returned by {@link #allocate()} when no IDs remain. */
    public static final int NO_ID = -1;

    private final Deque<Integer> _freeIds = new ArrayDeque<>(
            NetworkConstants.MINECRAFT_NET_MAX_PLAYERS - NetworkConstants.XUSER_MAX_COUNT);

    public SmallIdPool() {
        // Pre-populate in ascending order. Combined with LIFO pop/push (pollFirst/addFirst),
        // a fresh pool hands out 4, 5, 6, ... — matching the source's s_nextSmallId-starts-at-4 behaviour.
        for (int id = NetworkConstants.XUSER_MAX_COUNT; id < NetworkConstants.MINECRAFT_NET_MAX_PLAYERS; id++) {
            _freeIds.addLast(id);
        }
    }

    /**
     * Allocate the next available small ID.
     *
     * @return a small ID in {@code [XUSER_MAX_COUNT, MINECRAFT_NET_MAX_PLAYERS)}, or {@link #NO_ID}
     *         if the pool is exhausted. Callers receiving {@link #NO_ID} must send the reject frame
     *         and close the socket per §3.2.
     */
    public int allocate() {
        synchronized (_freeIds) {
            Integer id = _freeIds.pollFirst();
            return (id == null) ? NO_ID : id;
        }
    }

    /**
     * Return a small ID to the pool. LIFO: the next {@link #allocate()} after a {@code free(n)} will
     * return {@code n}. Double-frees and out-of-range IDs throw — they indicate a bug in the caller.
     */
    public void free(int id) {
        if (id < NetworkConstants.XUSER_MAX_COUNT || id >= NetworkConstants.MINECRAFT_NET_MAX_PLAYERS) {
            throw new IllegalArgumentException("small ID out of range: " + id);
        }
        synchronized (_freeIds) {
            // Cheap sanity check: O(n) but n<=252 and this only fires on disconnect.
            if (_freeIds.contains(id)) {
                throw new IllegalStateException("double-free of small ID: " + id);
            }
            _freeIds.addFirst(id);
        }
    }

    /** Current number of free IDs. Useful for logging/metrics and tests. */
    public int available() {
        synchronized (_freeIds) {
            return _freeIds.size();
        }
    }

    /** Total capacity of the pool (constant: {@code MINECRAFT_NET_MAX_PLAYERS - XUSER_MAX_COUNT}). */
    public int capacity() {
        return NetworkConstants.MINECRAFT_NET_MAX_PLAYERS - NetworkConstants.XUSER_MAX_COUNT;
    }
}

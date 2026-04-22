package dev.drperky.lce.minecraft.network.utils;

import java.util.ArrayDeque;
import java.util.Deque;

public class SmallIdPool {
    private final Deque<Integer> _freeIds = new ArrayDeque<>();

    public SmallIdPool() {
        synchronized (_freeIds) {
            for (int i = 4; i <= 255; i++) {
                _freeIds.addLast(i);
            }
        }

    }

    public Integer allocate() {
        synchronized (_freeIds) {
            return _freeIds.pollFirst(); // null if none available
        }
    }

    public void free(int id) {
        synchronized (_freeIds) {
            if (id >= 4 && id <= 255) {
                _freeIds.addFirst(id);
            }
        }
    }
}

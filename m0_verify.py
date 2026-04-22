"""
Live M0 verification driver. Runs the four scenarios from PLAN.md Section 3 M0 Verification against a
running EndCity server on localhost:25565. Exit code 0 iff all four pass.
"""
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 25565
EDISCONNECT_SERVER_FULL = 12
CAPACITY = 252  # MINECRAFT_NET_MAX_PLAYERS - XUSER_MAX_COUNT


def recv_exact(sock, n, timeout=2.0):
    sock.settimeout(timeout)
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return buf
        buf += chunk
    return buf


def test_single_connect_receives_small_id():
    """Verification item 1: 'Connect a raw TCP client, verify you receive a single byte back.'"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((HOST, PORT))
    b = recv_exact(s, 1)
    s.close()
    assert len(b) == 1, f"expected 1 byte, got {len(b)}"
    small_id = b[0]
    assert 4 <= small_id < 256, f"small_id {small_id} out of range [4,256)"
    print(f"  [OK] single connect got small_id={small_id}")
    return small_id


def test_pool_exhaustion_sends_reject_frame():
    """Verification item 2: 'Connect 252 raw clients, verify the 253rd gets a 6-byte reject frame.'"""
    socks = []
    try:
        for i in range(CAPACITY):
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.connect((HOST, PORT))
            b = recv_exact(s, 1, timeout=5.0)
            assert len(b) == 1, f"connection {i}: expected 1 byte, got {len(b)}"
            socks.append(s)
            # Small pacing so the accept thread has time to process each one.
            if i % 50 == 49:
                time.sleep(0.05)
        print(f"  [OK] accepted all {CAPACITY} connections, each got a small ID")

        # The 253rd (0-indexed: 252nd after the loop) should get the reject frame.
        reject_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        reject_sock.connect((HOST, PORT))
        frame = recv_exact(reject_sock, 6, timeout=5.0)
        reject_sock.close()

        assert len(frame) == 6, f"expected 6-byte reject frame, got {len(frame)} bytes: {frame.hex()}"
        assert frame[0] == 0xFF, f"byte 0: expected 0xFF sentinel, got 0x{frame[0]:02x}"
        assert frame[1] == 0xFF, f"byte 1: expected 0xFF DisconnectPacket id, got 0x{frame[1]:02x}"
        reason = struct.unpack(">i", frame[2:6])[0]
        assert reason == EDISCONNECT_SERVER_FULL, f"bytes 2-5: expected reason={EDISCONNECT_SERVER_FULL} (eDisconnect_ServerFull), got {reason}"
        print(f"  [OK] 253rd connect got 6-byte reject frame: {frame.hex()} (reason=eDisconnect_ServerFull={reason})")
    finally:
        for s in socks:
            try: s.close()
            except Exception: pass


def test_mid_pool_disconnect_recycles_small_id():
    """Verification item 3: 'Disconnect a mid-pool connection, reconnect, verify the same small ID is reused.'"""
    # Give the server time to drain the previous test's connections.
    time.sleep(2.0)

    socks = []
    ids = []
    # Fill partway — 10 is enough to have something "mid-pool".
    for _ in range(10):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((HOST, PORT))
        b = recv_exact(s, 1)
        socks.append(s)
        ids.append(b[0])

    # Drop the middle one.
    victim_id = ids[5]
    socks[5].close()
    print(f"  dropped connection with small_id={victim_id}")

    # Give the server its second-or-so to tear down and return the ID.
    time.sleep(1.5)

    # New connection should get the freed ID back (LIFO pool, freed ID is on the head).
    new = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    new.connect((HOST, PORT))
    b = recv_exact(new, 1)
    new_id = b[0]
    new.close()
    for s in socks:
        try: s.close()
        except Exception: pass

    assert new_id == victim_id, f"expected recycled small_id={victim_id}, got {new_id}"
    print(f"  [OK] mid-pool disconnect recycled small_id={victim_id}")


def test_kill_client_triggers_cleanup_under_1s():
    """Verification item 4: 'Kill ncat client-side, verify server cleans up within 1 second and the small ID returns to the pool.'"""
    time.sleep(2.0)

    # Baseline: what small_id does a fresh connection get?
    s1 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s1.connect((HOST, PORT))
    id1 = recv_exact(s1, 1)[0]

    # Hard-kill the socket — set SO_LINGER to force RST instead of graceful FIN.
    linger = struct.pack("ii", 1, 0)
    s1.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, linger)
    s1.close()

    # Wait <1s and check that the ID came back to the pool.
    time.sleep(0.8)

    s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s2.connect((HOST, PORT))
    id2 = recv_exact(s2, 1)[0]
    s2.close()

    assert id2 == id1, f"after killed connection, expected small_id={id1} to be back in pool, got {id2}"
    print(f"  [OK] hard-killed socket cleaned up in <1s; small_id={id1} reused")


def main():
    print(">>> Test 1: single connect receives small ID")
    test_single_connect_receives_small_id()

    print(">>> Test 2: pool exhaustion sends 6-byte reject frame")
    test_pool_exhaustion_sends_reject_frame()

    print(">>> Test 3: mid-pool disconnect recycles small ID")
    test_mid_pool_disconnect_recycles_small_id()

    print(">>> Test 4: killed client cleaned up in <1s")
    test_kill_client_triggers_cleanup_under_1s()

    print("ALL M0 VERIFICATION ITEMS PASSED")


if __name__ == "__main__":
    try:
        main()
        sys.exit(0)
    except AssertionError as e:
        print(f"FAIL: {e}")
        sys.exit(1)

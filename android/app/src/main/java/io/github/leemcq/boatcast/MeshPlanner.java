package io.github.leemcq.boatcast;

import java.util.Collection;
import java.util.Map;

/**
 * The mesh's decisions, kept free of Android code so they can be unit-tested and simulated:
 * which node to join, when to move to a better one, and when to become a repeater.
 *
 * The mesh is a tree rooted at the host. Every node knows its depth (hops from the host).
 * Loops cannot form: a node that already serves children only moves to a parent that is
 * strictly shallower than its current one, and its children are always deeper than it.
 */
final class MeshPlanner {
    /** Phones one hotspot is counted on serving. Android phones usually allow about 10. */
    static final int CAPACITY = 8;
    /** Depth advertised by a phone that is looking for a mesh and has not joined yet. */
    static final int SEEKING = 255;
    /** A node not heard for this long is gone. */
    static final long STALE_MS = 15_000;
    /** Weaker than this and the link is too poor to carry the stream reliably. */
    static final int MIN_RSSI = -85;
    /** A move must win by this much, so phones do not flap between similar parents. */
    static final int HYSTERESIS = 60;
    /** And not more often than this. */
    static final long MIN_SWITCH_GAP_MS = 60_000;

    private MeshPlanner() {}

    static final class Node {
        final int mesh, id, depth, free, battery, rssi;
        final boolean charging, accepting;
        final long seenMs;

        Node(int mesh, int id, int depth, int free, int battery, boolean charging, boolean accepting, int rssi, long seenMs) {
            this.mesh = mesh;
            this.id = id;
            this.depth = depth;
            this.free = free;
            this.battery = battery;
            this.charging = charging;
            this.accepting = accepting;
            this.rssi = rssi;
            this.seenMs = seenMs;
        }

        boolean seeking() {
            return depth >= SEEKING;
        }

        @Override
        public String toString() {
            return "node " + id + " depth " + depth + " free " + free + " rssi " + rssi;
        }
    }

    /**
     * Lower is better: fewer hops first (each hop adds delay), then spare room
     * (spread the load), then signal strength, then spare the battery of low phones.
     */
    static int score(Node n) {
        int s = n.depth * 100;
        s += (CAPACITY - Math.max(0, Math.min(CAPACITY, n.free))) * 8;
        s += Math.max(0, -55 - n.rssi) * 2;
        if (n.battery < 30 && !n.charging) s += 150;
        return s;
    }

    /**
     * Best node to join, or null.
     *
     * @param myMesh   the mesh we belong to (0 = any)
     * @param maxDepth only nodes shallower than this (loop guard when we have children)
     * @param avoid    node id → time until which it is avoided (it just failed us)
     */
    static Node choose(Collection<Node> nodes, int myMesh, int myId, int maxDepth, Map<Integer, Long> avoid, long now) {
        Node best = null;
        for (Node n : nodes) {
            if (n.id == myId || n.seeking() || !n.accepting || n.free <= 0) continue;
            if (now - n.seenMs > STALE_MS || n.rssi < MIN_RSSI || n.depth >= maxDepth) continue;
            if (myMesh != 0 && n.mesh != myMesh) continue;
            Long until = avoid.get(n.id);
            if (until != null && until > now) continue;
            if (best == null || score(n) < score(best)) best = n;
        }
        return best;
    }

    /** How long an orphaned repeater waits for its subtree to hear that it lost its parent. */
    static final long POISON_WAIT_MS = 36_000;

    /**
     * Loop guard: the deepest parent allowed. A node without children may join anyone.
     * A connected node with children may only move shallower than itself. An orphan with
     * children first tries shallower than it was; once its subtree has had time to learn
     * it is cut off (and stopped advertising), it may join any node still advertising.
     */
    static int maxParentDepth(boolean hasChildren, boolean orphan, int myDepth, long msSinceOrphaned) {
        if (!hasChildren) return SEEKING;
        if (!orphan) return myDepth;
        return msSinceOrphaned < POISON_WAIT_MS ? myDepth : SEEKING;
    }

    /** Move from the current parent to a better one? */
    static boolean shouldSwitch(Node current, Node best, long msSinceLastSwitch) {
        if (current == null || best == null || best.id == current.id) return false;
        if (msSinceLastSwitch < MIN_SWITCH_GAP_MS) return false;
        return score(best) + HYSTERESIS < score(current);
    }

    /**
     * Run a hotspot for others? Repeat when phones are looking for a way in, or when too
     * few repeaters with room are within reach. Keep repeating while serving phones.
     * Stop when the battery gets low, so the children move to a healthier repeater.
     */
    static boolean shouldRepeat(boolean capable, int battery, boolean charging, boolean repeating,
                                int myChildren, int nearbyRepeatersWithRoom, int seekersNearby) {
        if (!capable) return false;
        int floor = repeating ? 20 : 30;
        if (!charging && battery < floor) return false;
        if (repeating && myChildren > 0) return true;
        if (seekersNearby > 0) return true;
        // Keep two spare repeaters in reach; above four, idle repeaters stand down.
        return repeating ? nearbyRepeatersWithRoom < 4 : nearbyRepeatersWithRoom < 2;
    }

    /** Eight bytes of BLE service data: who we are and how much room we have. */
    static byte[] encode(int mesh, int id, int depth, int free, int battery, boolean charging, boolean accepting) {
        return new byte[] {
            (byte) (mesh >> 8), (byte) mesh, (byte) (id >> 8), (byte) id,
            (byte) Math.min(255, depth), (byte) Math.max(0, Math.min(255, free)),
            (byte) Math.max(0, Math.min(100, battery)),
            (byte) ((accepting ? 1 : 0) | (charging ? 2 : 0))
        };
    }

    static Node decode(byte[] b, int rssi, long now) {
        if (b == null || b.length < 8) return null;
        int mesh = (b[0] & 0xff) << 8 | (b[1] & 0xff);
        int id = (b[2] & 0xff) << 8 | (b[3] & 0xff);
        return new Node(mesh, id, b[4] & 0xff, b[5] & 0xff, b[6] & 0xff, (b[7] & 2) != 0, (b[7] & 1) != 0, rssi, now);
    }
}

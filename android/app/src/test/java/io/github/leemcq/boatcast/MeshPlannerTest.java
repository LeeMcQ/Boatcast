package io.github.leemcq.boatcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Unit tests for the mesh rules, plus a simulation of a room full of phones: everyone
 * must get connected, no hotspot over capacity, no loops, and the mesh must heal when
 * repeaters leave or run low on battery. Radio is modelled simply (signal falls with
 * distance), so this checks the rules, not real Wi-Fi.
 */
public class MeshPlannerTest {

    private static MeshPlanner.Node node(int id, int depth, int free, int rssi) {
        return new MeshPlanner.Node(1, id, depth, free, 80, false, true, rssi, 0);
    }

    @Test
    public void encodeDecodeRoundTrip() {
        byte[] b = MeshPlanner.encode(4660, 513, 2, 5, 77, true, true);
        assertEquals(8, b.length);
        MeshPlanner.Node n = MeshPlanner.decode(b, -60, 123);
        assertEquals(4660, n.mesh);
        assertEquals(513, n.id);
        assertEquals(2, n.depth);
        assertEquals(5, n.free);
        assertEquals(77, n.battery);
        assertTrue(n.charging);
        assertTrue(n.accepting);
    }

    @Test
    public void prefersShallowThenRoomThenSignal() {
        List<MeshPlanner.Node> l = new ArrayList<>();
        l.add(node(1, 2, 8, -50));
        l.add(node(2, 1, 1, -70));
        l.add(node(3, 1, 6, -70));
        assertEquals(3, MeshPlanner.choose(l, 1, 99, 255, new HashMap<>(), 0).id);
    }

    @Test
    public void skipsFullWeakStaleAvoidedAndOtherMeshes() {
        List<MeshPlanner.Node> l = new ArrayList<>();
        l.add(node(1, 1, 0, -50));                                         // full
        l.add(node(2, 1, 5, -90));                                         // too weak
        l.add(new MeshPlanner.Node(1, 3, 1, 5, 80, false, true, -50, -20_000)); // stale
        l.add(new MeshPlanner.Node(2, 4, 1, 5, 80, false, true, -50, 0));  // other mesh
        l.add(node(5, 1, 5, -50));                                         // avoided
        Map<Integer, Long> avoid = new HashMap<>();
        avoid.put(5, 10_000L);
        assertNull(MeshPlanner.choose(l, 1, 99, 255, avoid, 0));
    }

    @Test
    public void loopGuardOnlyAllowsShallowerParents() {
        List<MeshPlanner.Node> l = new ArrayList<>();
        l.add(node(1, 3, 5, -50));
        assertNull(MeshPlanner.choose(l, 1, 99, 3, new HashMap<>(), 0));
        assertNotNull(MeshPlanner.choose(l, 1, 99, 4, new HashMap<>(), 0));
    }

    @Test
    public void switchNeedsAClearWinAndAGap() {
        MeshPlanner.Node cur = node(1, 2, 4, -70);
        MeshPlanner.Node slightlyBetter = node(2, 2, 5, -68);
        MeshPlanner.Node muchBetter = node(3, 1, 6, -60);
        assertFalse(MeshPlanner.shouldSwitch(cur, slightlyBetter, 120_000));
        assertFalse(MeshPlanner.shouldSwitch(cur, muchBetter, 10_000));
        assertTrue(MeshPlanner.shouldSwitch(cur, muchBetter, 120_000));
    }

    @Test
    public void repeatRules() {
        assertFalse(MeshPlanner.shouldRepeat(false, 90, true, false, 0, 0, 5));  // phone cannot
        assertFalse(MeshPlanner.shouldRepeat(true, 25, false, false, 0, 0, 5));  // battery low
        assertTrue(MeshPlanner.shouldRepeat(true, 25, true, false, 0, 0, 5));    // charging is fine
        assertTrue(MeshPlanner.shouldRepeat(true, 60, false, false, 0, 0, 1));   // someone is looking
        assertTrue(MeshPlanner.shouldRepeat(true, 60, false, false, 0, 1, 0));   // too few spares
        assertFalse(MeshPlanner.shouldRepeat(true, 60, false, false, 0, 3, 0));  // enough spares
        assertTrue(MeshPlanner.shouldRepeat(true, 60, false, true, 2, 9, 0));    // serving phones
        assertFalse(MeshPlanner.shouldRepeat(true, 15, false, true, 2, 9, 0));   // hand over, battery
        assertFalse(MeshPlanner.shouldRepeat(true, 60, false, true, 0, 5, 0));   // idle, many spares
    }

    // ------------------------------------------------------------ simulation

    private static final class Phone {
        final int id;
        final double x, y;
        final boolean capable;
        int battery;
        boolean charging;
        boolean alive = true;
        boolean repeating;
        Phone parent;
        int depth = MeshPlanner.SEEKING;
        long lastSwitch = -1_000_000;
        long orphanSince;
        int oldDepth;
        final List<Phone> children = new ArrayList<>();

        Phone(int id, double x, double y, boolean capable, int battery) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.capable = capable;
            this.battery = battery;
        }
    }

    private static final class Room {
        final Random rnd;
        final Phone host;
        final List<Phone> phones = new ArrayList<>();
        final Map<Integer, Map<Integer, Long>> avoid = new HashMap<>();
        long now = 0;

        Room(int n, double w, double h, double capableShare, long seed) {
            rnd = new Random(seed);
            host = new Phone(0, w / 2, h / 2, true, 100);
            host.repeating = true;
            host.depth = 0;
            host.charging = true;
            for (int i = 1; i <= n; i++) {
                phones.add(new Phone(i, rnd.nextDouble() * w, rnd.nextDouble() * h,
                        rnd.nextDouble() < capableShare, 40 + rnd.nextInt(61)));
            }
        }

        static int rssi(Phone a, Phone b) {
            double d = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
            return (int) Math.round(-40 - 25 * Math.log10(d));
        }

        List<Phone> all() {
            List<Phone> l = new ArrayList<>(phones);
            l.add(host);
            return l;
        }

        void detach(Phone p) {
            if (p.parent != null) p.parent.children.remove(p);
            p.parent = null;
        }

        /**
         * A repeater that loses its parent "poisons" its subtree (advertises no depth), so
         * no phone below it is chosen as its new parent. The app does the same: the server
         * reports no depth while its upstream is down, and children copy it on their next ping.
         */
        void orphan(Phone p) {
            p.orphanSince = now;
            p.oldDepth = p.depth;
            poison(p);
        }

        void poison(Phone p) {
            p.depth = MeshPlanner.SEEKING;
            for (Phone c : p.children) poison(c);
        }

        void setDepth(Phone p, int d) {
            p.depth = d;
            for (Phone c : p.children) setDepth(c, d + 1);
        }

        MeshPlanner.Node view(Phone from, Phone q) {
            return new MeshPlanner.Node(1, q.id, q.depth, MeshPlanner.CAPACITY - q.children.size(),
                    q.battery, q.charging, true, rssi(from, q), now);
        }

        List<MeshPlanner.Node> heard(Phone p) {
            List<MeshPlanner.Node> l = new ArrayList<>();
            for (Phone q : all()) {
                if (q != p && q.alive && q.repeating && q.depth < MeshPlanner.SEEKING) l.add(view(p, q));
            }
            return l;
        }

        Phone byId(int id) {
            if (id == 0) return host;
            return phones.get(id - 1);
        }

        void attach(Phone p, Phone parent) {
            detach(p);
            p.parent = parent;
            parent.children.add(p);
            setDepth(p, parent.depth + 1);
        }

        void round() {
            now += 4_000; // one planner tick
            // 1. Lost parents (gone, or stopped repeating).
            for (Phone p : phones) {
                if (!p.alive || p.parent == null) continue;
                if (!p.parent.alive || !p.parent.repeating) {
                    avoid.computeIfAbsent(p.id, k -> new HashMap<>()).put(p.parent.id, now + 60_000);
                    detach(p);
                    orphan(p);
                }
            }
            List<Phone> order = new ArrayList<>(phones);
            Collections.shuffle(order, rnd);
            // 2. Join (or move to a much better parent).
            for (Phone p : order) {
                if (!p.alive) continue;
                int maxDepth = MeshPlanner.maxParentDepth(!p.children.isEmpty(), p.parent == null,
                        p.parent == null ? p.oldDepth : p.depth, now - p.orphanSince);
                Map<Integer, Long> av = avoid.computeIfAbsent(p.id, k -> new HashMap<>());
                MeshPlanner.Node best = MeshPlanner.choose(heard(p), 1, p.id, maxDepth, av, now);
                if (best == null) continue;
                if (p.parent == null) {
                    attach(p, byId(best.id));
                } else if (MeshPlanner.shouldSwitch(view(p, p.parent), best, now - p.lastSwitch)) {
                    attach(p, byId(best.id));
                    p.lastSwitch = now;
                }
            }
            // 3. Repeat or stand down.
            for (Phone p : order) {
                if (!p.alive || p.parent == null) continue;
                int room = 0, seekers = 0;
                for (Phone q : all()) {
                    if (q == p || !q.alive) continue;
                    if (q.repeating && q.children.size() < MeshPlanner.CAPACITY && rssi(p, q) > -75) room++;
                    if (q.parent == null && q != host && rssi(p, q) >= MeshPlanner.MIN_RSSI) seekers++;
                }
                boolean should = MeshPlanner.shouldRepeat(p.capable, p.battery, p.charging, p.repeating,
                        p.children.size(), room, seekers);
                if (p.repeating && !should) {
                    p.repeating = false;
                    for (Phone c : new ArrayList<>(p.children)) {
                        detach(c);
                        orphan(c);
                    }
                } else if (!p.repeating && should) {
                    p.repeating = true;
                }
            }
        }

        void run(int rounds) {
            for (int i = 0; i < rounds; i++) round();
        }

        int unconnected() {
            int n = 0;
            for (Phone p : phones) if (p.alive && !reachesHost(p)) n++;
            return n;
        }

        boolean reachesHost(Phone p) {
            Phone cur = p;
            for (int hops = 0; hops < 64; hops++) {
                if (cur == host) return true;
                if (cur.parent == null || !cur.alive) return false;
                cur = cur.parent;
            }
            return false; // a loop
        }

        int maxDepth() {
            int m = 0;
            for (Phone p : phones) if (p.alive && reachesHost(p)) m = Math.max(m, p.depth);
            return m;
        }

        void assertHealthy(String when) {
            for (Phone p : all()) {
                assertTrue(when + ": hotspot over capacity", p.children.size() <= MeshPlanner.CAPACITY);
            }
            assertEquals(when + ": phones left without a path to the host", 0, unconnected());
        }
    }

    @Test
    public void hundredPhonesAllConnectAndStayShallow() {
        Room r = new Room(100, 60, 40, 0.7, 1);
        r.run(30);
        r.assertHealthy("100 phones");
        assertTrue("depth " + r.maxDepth(), r.maxDepth() <= 3);
    }

    @Test
    public void thousandPhonesAllConnect() {
        Room r = new Room(1000, 80, 60, 0.7, 2);
        r.run(60);
        r.assertHealthy("1,000 phones");
        assertTrue("depth " + r.maxDepth(), r.maxDepth() <= 5);
    }

    @Test
    public void healsWhenRepeatersLeave() {
        Room r = new Room(120, 60, 40, 0.7, 3);
        r.run(30);
        r.assertHealthy("before");
        int removed = 0;
        for (Phone p : r.phones) {
            if (p.repeating && !p.children.isEmpty() && removed < 5) {
                p.alive = false;
                r.detach(p);
                removed++;
            }
        }
        assertEquals(5, removed);
        r.run(20);
        r.assertHealthy("after 5 busy repeaters left");
    }

    @Test
    public void lowBatteryRepeaterHandsOver() {
        Room r = new Room(80, 50, 40, 0.7, 4);
        r.run(30);
        Phone tired = null;
        for (Phone p : r.phones) if (p.repeating && !p.children.isEmpty()) { tired = p; break; }
        assertNotNull(tired);
        tired.battery = 15;
        tired.charging = false;
        r.run(20);
        assertFalse("low phone still repeating", tired.repeating);
        r.assertHealthy("after a low-battery hand-over");
    }

    @Test
    public void twentyRandomRoomsHealAfterRepeatersLeave() {
        for (long seed = 10; seed < 30; seed++) {
            Room r = new Room(150, 70, 45, 0.6, seed);
            r.run(30);
            r.assertHealthy("seed " + seed + " before");
            int removed = 0;
            for (Phone p : r.phones) {
                if (p.repeating && removed < 6 && r.rnd.nextBoolean()) {
                    p.alive = false;
                    r.detach(p);
                    removed++;
                }
            }
            r.run(25);
            r.assertHealthy("seed " + seed + " after " + removed + " repeaters left");
        }
    }

    @Test
    public void longHallNeedsHopsBeyondRadioRange() {
        // 240 m long: the far end is out of the host's reach, so the mesh must relay.
        Room r = new Room(200, 240, 20, 0.7, 5);
        r.run(60);
        r.assertHealthy("long hall");
        assertTrue("needed more than one hop", r.maxDepth() >= 2);
    }
}

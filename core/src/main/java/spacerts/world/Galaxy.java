package spacerts.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The set of map areas in a game. Classic: one 12000 x 12000 square. Hive: a hexagon of hexagons
 * (centre + RINGS rings = 91 systems) spread over one shared coordinate space with void between them.
 */
public class Galaxy {
    public static final float CLASSIC_SIZE = 12000f;
    public static final int RINGS = 5;
    /** Hive system circumradius and the empty gap between neighbouring hex edges. */
    public static final float HEX_R = 10800f, GAP = 2000f;
    private static final float SQRT3 = 1.7320508f;
    /** Lattice size: centre spacing between neighbours = SQRT3 * SPACING. */
    private static final float SPACING = HEX_R + GAP / SQRT3;
    /** Axial direction of each hex side k (side k faces angle 30 + 60k degrees). */
    public static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};

    public final boolean hive;
    public final List<StarSystem> systems = new ArrayList<>();
    public final float width, height;
    private final float ox, oy;
    /** Axial lookup: grid[(q + RINGS) * SIDE + (r + RINGS)] (a plain array: this sits on the hottest path). */
    private static final int SIDE = 2 * RINGS + 1;
    private final StarSystem[] grid = new StarSystem[SIDE * SIDE];
    /** next[from][to] = side of `from` whose gate leads one step closer to `to` (-1 when from == to). */
    private int[][] next;
    private int[][] hops;

    private Galaxy(boolean hive) {
        this.hive = hive;
        if (!hive) {
            width = height = CLASSIC_SIZE;
            ox = oy = 0;
            systems.add(new StarSystem(0, 0, 0, false, CLASSIC_SIZE / 2, CLASSIC_SIZE / 2, CLASSIC_SIZE / 2));
            return;
        }
        float halfW = SPACING * 1.5f * RINGS + HEX_R + 600;
        float halfH = SPACING * SQRT3 * RINGS + HEX_R * SQRT3 / 2 + 600;
        width = halfW * 2;
        height = halfH * 2;
        ox = halfW;
        oy = halfH;
        for (int q = -RINGS; q <= RINGS; q++)
            for (int r = Math.max(-RINGS, -q - RINGS); r <= Math.min(RINGS, -q + RINGS); r++) {
                StarSystem s = new StarSystem(systems.size(), q, r, true, ox + SPACING * 1.5f * q, oy + SPACING * SQRT3 * (r + q / 2f), HEX_R);
                systems.add(s);
                grid[(q + RINGS) * SIDE + (r + RINGS)] = s;
            }
        for (StarSystem s : systems)
            for (int k = 0; k < 6; k++) s.neighbours[k] = at(s.q + DIRS[k][0], s.r + DIRS[k][1]);
        buildRoutes();
        for (StarSystem s : systems) s.danger = jumps(home(), s);
    }

    /** Hive: the player's starting hex at the bottom of the hive (classic: the only system). */
    public StarSystem home() {
        return hive ? at(0, -RINGS) : systems.get(0);
    }

    /** Hive: the Pirate Citadel's hex at the very top. */
    public StarSystem citadel() {
        return hive ? at(0, RINGS) : null;
    }

    /** Highest danger in the hive (home to Citadel). */
    public int maxDanger() {
        return hive ? 2 * RINGS : 0;
    }

    public static Galaxy classic() { return new Galaxy(false); }
    public static Galaxy hive() { return new Galaxy(true); }

    public StarSystem centre() {
        return hive ? at(0, 0) : systems.get(0);
    }

    /** The hex at axial (q, r), or null outside the hive. */
    public StarSystem at(int q, int r) {
        if (q < -RINGS || q > RINGS || r < -RINGS || r > RINGS) return null;
        return grid[(q + RINGS) * SIDE + (r + RINGS)];
    }

    /** The system a point belongs to (the nearest one for points in the void between hexes). Never null. */
    public StarSystem systemAt(float x, float y) {
        if (!hive) return systems.get(0);
        float px = (x - ox) / SPACING, py = (y - oy) / SPACING;
        float fq = px * 2f / 3f, fr = -px / 3f + py * SQRT3 / 3f;
        // cube rounding
        float fs = -fq - fr;
        int q = Math.round(fq), r = Math.round(fr), s = Math.round(fs);
        float dq = Math.abs(q - fq), dr = Math.abs(r - fr), ds = Math.abs(s - fs);
        if (dq > dr && dq > ds) q = -r - s;
        else if (dr > ds) r = -q - s;
        StarSystem hit = at(q, r);
        if (hit != null) return hit;
        StarSystem best = systems.get(0);
        for (StarSystem c : systems) if (c.dist(x, y) < best.dist(x, y)) best = c;
        return best;
    }

    private int indexedSize = -1;
    private Body indexedLast;

    /** Forces the next indexBodies call to rebuild (after bodies were replaced wholesale, e.g. on load). */
    public void reindex() {
        indexedSize = -1;
    }

    /** Rebuilds every system's body list from the world's bodies and stamps each body with its system. */
    public void indexBodies(List<Body> bodies) {
        // bodies never move: only re-index when one was added (new last element) or removed (size changed)
        int n0 = bodies.size();
        Body last0 = n0 > 0 ? bodies.get(n0 - 1) : null;
        if (n0 == indexedSize && last0 == indexedLast) return;
        indexedSize = n0;
        indexedLast = last0;
        for (StarSystem s : systems) s.bodies.clear();
        for (int i = 0, n = bodies.size(); i < n; i++) {
            Body b = bodies.get(i);
            StarSystem s = systemAt(b.x, b.y);
            b.system = s;
            s.bodies.add(b);
        }
    }

    // ------------------------------------------------------------------ routing between systems

    private void buildRoutes() {
        int n = systems.size();
        next = new int[n][n];
        hops = new int[n][n];
        for (int[] row : next) Arrays.fill(row, -1);
        for (int[] row : hops) Arrays.fill(row, Integer.MAX_VALUE);
        // BFS from every target: next[from][to] is the side of `from` that steps toward `to`
        for (StarSystem to : systems) {
            ArrayDeque<StarSystem> open = new ArrayDeque<>();
            hops[to.id][to.id] = 0;
            open.add(to);
            while (!open.isEmpty()) {
                StarSystem cur = open.poll();
                for (int k = 0; k < 6; k++) {
                    StarSystem nb = cur.neighbours[k];
                    if (nb == null || hops[nb.id][to.id] != Integer.MAX_VALUE) continue;
                    hops[nb.id][to.id] = hops[cur.id][to.id] + 1;
                    next[nb.id][to.id] = (k + 3) % 6; // nb reaches cur through its opposite side
                    open.add(nb);
                }
            }
        }
    }

    /** Number of jumps between two systems (0 for the same one). */
    public int jumps(StarSystem a, StarSystem b) {
        if (a == b || !hive) return 0;
        return hops[a.id][b.id];
    }

    /** The gate in `from` to fly through next on the way to `to`, or null when already there. */
    public Body nextGate(StarSystem from, StarSystem to) {
        if (from == to || !hive) return null;
        int side = next[from.id][to.id];
        return side < 0 ? null : from.gates[side];
    }
}

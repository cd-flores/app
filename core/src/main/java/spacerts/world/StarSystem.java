package spacerts.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One map area. Classic games have a single square system covering the whole field; the Hive galaxy has 91
 * flat-top hexagonal systems laid out on a lattice with empty void between them, joined by jump gates.
 */
public class StarSystem {
    private static final float SQRT3 = 1.7320508f;

    public final int id;
    /** Axial hex coordinates and ring (distance from the centre hex); 0 for the classic square. */
    public final int q, r, ring;
    public final boolean hex;
    public final float cx, cy;
    /** Hex: circumradius. Square: half the side length. */
    public final float radius;
    public String name = "";
    public SystemArchetype archetype = SystemArchetype.CLASSIC;

    /** Hive conquest: who holds the hex. GREY = nobody, OWNED = player beacon, CONTESTED = owned but pirates inside. */
    public enum Control { GREY, OWNED, CONTESTED, PIRATE }

    public Control control = Control.GREY;
    /** Jumps from the player's starting hex (0 at home, 10 at the Citadel): pirate strength rises with it. */
    public int danger;
    /** Pirate ships currently inside (recounted by ConquestSystem). */
    public int pirateShips;

    public boolean owned() {
        return control == Control.OWNED || control == Control.CONTESTED;
    }

    /** Bodies inside this system (rebuilt from World.bodies every tick by Galaxy.indexBodies). */
    public final List<Body> bodies = new ArrayList<>();
    /** Jump gate on each hex side (index = side 0..5), null where there is no neighbour. */
    public final Body[] gates = new Body[6];
    /** Neighbouring system on each side, null at the rim. */
    public final StarSystem[] neighbours = new StarSystem[6];

    public StarSystem(int id, int q, int r, boolean hex, float cx, float cy, float radius) {
        this.id = id;
        this.q = q;
        this.r = r;
        this.ring = (Math.abs(q) + Math.abs(r) + Math.abs(-q - r)) / 2;
        this.hex = hex;
        this.cx = cx;
        this.cy = cy;
        this.radius = radius;
    }

    // ------------------------------------------------------------------ geometry

    /** Gauge of (x, y) relative to the shape shrunk by margin: <= 1 inside, > 1 outside. */
    private float gauge(float x, float y, float margin) {
        float dx = Math.abs(x - cx), dy = Math.abs(y - cy);
        if (!hex) {
            float h = Math.max(1f, radius - margin);
            return Math.max(dx, dy) / h;
        }
        float rr = Math.max(1f, radius - margin * 2f / SQRT3); // inset hex keeps an even margin on every side
        return Math.max(dy / (SQRT3 / 2f * rr), (SQRT3 * dx + dy) / (SQRT3 * rr));
    }

    /** True when (x, y) is at least margin inside the system's edge. */
    public boolean inside(float x, float y, float margin) {
        return gauge(x, y, margin) <= 1f;
    }

    public boolean contains(float x, float y) {
        return inside(x, y, 0);
    }

    /** X of (x, y) pulled back inside the system (40 from the edge). Squares clamp per axis, hexes toward the centre. */
    public float clampX(float x, float y) {
        if (!hex) return Math.max(cx - radius + 40f, Math.min(cx + radius - 40f, x));
        float g = gauge(x, y, 40f);
        return g <= 1f ? x : cx + (x - cx) / g;
    }

    public float clampY(float x, float y) {
        if (!hex) return Math.max(cy - radius + 40f, Math.min(cy + radius - 40f, y));
        float g = gauge(x, y, 40f);
        return g <= 1f ? y : cy + (y - cy) / g;
    }

    /** Bounding box. */
    public float minX() { return cx - radius; }
    public float maxX() { return cx + radius; }
    public float minY() { return cy - (hex ? radius * SQRT3 / 2f : radius); }
    public float maxY() { return cy + (hex ? radius * SQRT3 / 2f : radius); }

    /** A random point at least margin inside the system. */
    public float[] randomPoint(Random rng, float margin) {
        for (int i = 0; i < 200; i++) {
            float x = minX() + rng.nextFloat() * (maxX() - minX());
            float y = minY() + rng.nextFloat() * (maxY() - minY());
            if (inside(x, y, margin)) return new float[]{x, y};
        }
        return new float[]{cx, cy};
    }

    /** Corner k (0..5) of the hex, flat-top: corner 0 points east. */
    public float cornerX(int k) { return cx + radius * (float) Math.cos(Math.PI / 3 * k); }
    public float cornerY(int k) { return cy + radius * (float) Math.sin(Math.PI / 3 * k); }

    /** Middle of hex side k (between corners k and k+1), pulled in by inset. */
    public float sideX(int k, float inset) {
        double a = Math.PI / 3 * k + Math.PI / 6;
        return cx + (float) Math.cos(a) * (radius * SQRT3 / 2f - inset);
    }

    public float sideY(int k, float inset) {
        double a = Math.PI / 3 * k + Math.PI / 6;
        return cy + (float) Math.sin(a) * (radius * SQRT3 / 2f - inset);
    }

    public float dist(float x, float y) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    @Override
    public String toString() {
        return name.isEmpty() ? "System " + id : name;
    }
}

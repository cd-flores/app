package spacerts.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the pirates-only Hive: 91 hex systems, each filled according to its archetype, with a pair of linked
 * jump gates on every shared edge. The player starts in the bottom hex; every hex's danger is its jump distance
 * from home, so the climb to the Pirate Citadel at the top gets harder hex by hex.
 */
public final class HiveGenerator {
    private HiveGenerator() {}

    /** Pirate Havens to start with, at least this danger and spread apart. */
    public static final int START_HAVENS = 6, HAVEN_DANGER_MIN = 4;
    /** Gates sit this far inside the middle of each hex side. */
    public static final float GATE_INSET = 450f, GATE_RADIUS = 60f;
    /** Chance an Asteroid Belt hex starts with a giant asteroid. */
    public static final float BELT_GIANT_CHANCE = 0.35f;

    public static void generate(World w) {
        Random r = new Random(w.seed);
        Galaxy g = w.galaxy;
        assignArchetypes(g, r);
        for (StarSystem s : g.systems) s.name = GalaxyGenerator.name(r);
        g.home().name = "Sol Haven";
        g.citadel().name = "Blackreef Citadel";
        placeGates(w, g);
        for (StarSystem s : g.systems) fill(w, r, s);
        for (Body b : w.bodies) if (b.kind == Body.Kind.ASTEROID) w.initialAsteroids++;

        StarSystem home = g.home();
        GalaxyGenerator.startingFleet(w, w.player, home.cx, home.cy);
        PirateBases.seedStart(w, r);
        GalaxyGenerator.finish(w);
    }

    // ------------------------------------------------------------------ archetypes

    private static void assignArchetypes(Galaxy g, Random r) {
        StarSystem home = g.home();
        home.archetype = SystemArchetype.HOME;
        g.citadel().archetype = SystemArchetype.CITADEL;
        // pirate havens: deep in the hive and spread apart
        List<StarSystem> deep = new ArrayList<>();
        for (StarSystem s : g.systems) if (s.danger >= HAVEN_DANGER_MIN && s.archetype == SystemArchetype.CLASSIC) deep.add(s);
        java.util.Collections.shuffle(deep, r);
        List<StarSystem> havens = new ArrayList<>();
        for (StarSystem s : deep) {
            if (havens.size() >= START_HAVENS) break;
            boolean spaced = g.jumps(s, g.citadel()) >= 2;
            for (StarSystem h : havens) if (g.jumps(s, h) < 3) spaced = false;
            if (spaced) { s.archetype = SystemArchetype.HAVEN; havens.add(s); }
        }
        // 2 ancient ruins high up
        int ruins = 0;
        for (StarSystem s : deep) {
            if (ruins >= 2) break;
            if (s.archetype != SystemArchetype.CLASSIC || s.danger < 6) continue;
            s.archetype = SystemArchetype.RUINS;
            ruins++;
        }
        // home's neighbours give the player room to grow: a garden and an asteroid belt at least
        int k0 = 0;
        for (int k = 0; k < 6; k++) {
            StarSystem s = home.neighbours[k];
            if (s == null) continue;
            s.archetype = k0 == 0 ? SystemArchetype.GARDEN : k0 == 1 ? SystemArchetype.BELT : SystemArchetype.NURSERY;
            k0++;
        }
        for (StarSystem s : g.systems)
            if (s.archetype == SystemArchetype.CLASSIC) s.archetype = roll(r, s.danger);
    }

    private static SystemArchetype roll(Random r, int danger) {
        SystemArchetype[] kinds = {SystemArchetype.NURSERY, SystemArchetype.BELT, SystemArchetype.GARDEN, SystemArchetype.FORTRESS,
                SystemArchetype.FRONTIER, SystemArchetype.NEBULA, SystemArchetype.VOID};
        int[] weights = {12, 16, danger <= 4 ? 15 : 6, danger >= 2 ? 6 + danger : 0, danger >= 2 ? 13 : 4, 8, danger >= 2 ? 7 : 2};
        int total = 0;
        for (int wgt : weights) total += wgt;
        int roll = r.nextInt(total);
        for (int i = 0; i < kinds.length; i++) {
            roll -= weights[i];
            if (roll < 0) return kinds[i];
        }
        return SystemArchetype.BELT;
    }

    // ------------------------------------------------------------------ gates

    private static void placeGates(World w, Galaxy g) {
        for (StarSystem s : g.systems)
            for (int k = 0; k < 6; k++) {
                StarSystem nb = s.neighbours[k];
                if (nb == null || nb.id < s.id) continue; // each pair once
                int back = (k + 3) % 6;
                Body a = gate(s, k, nb), b = gate(nb, back, s);
                a.link = b;
                b.link = a;
                w.bodies.add(a);
                w.bodies.add(b);
            }
    }

    private static Body gate(StarSystem s, int side, StarSystem to) {
        Body b = new Body(Body.Kind.GATE, s.sideX(side, GATE_INSET), s.sideY(side, GATE_INSET), GATE_RADIUS);
        b.side = side;
        b.system = s;
        b.name = "Gate to " + to.name;
        b.color.set(0.45f, 0.85f, 1f, 1f);
        s.gates[side] = b;
        return b;
    }

    // ------------------------------------------------------------------ filling a hex

    /** Hexes are huge (EVE scale): each is filled in this many passes, so it holds ~3x the content, spread out. */
    public static final int CONTENT_PASSES = 3;

    private static void fill(World w, Random r, StarSystem s) {
        int start = w.bodies.size();
        for (int pass = 0; pass < CONTENT_PASSES; pass++) {
            boolean fixed = s.archetype == SystemArchetype.HOME || s.archetype == SystemArchetype.RUINS || s.archetype == SystemArchetype.CITADEL;
            if (pass > 0 && fixed) { outskirts(w, r, s, start); continue; }
            // belt bands cluster around the centre on the first pass, elsewhere in the hex on later ones
            float[] c = pass == 0 ? new float[]{s.cx, s.cy} : null;
            for (int i = 0; i < 40 && c == null; i++) {
                float[] p = s.randomPoint(r, 1500);
                if (Math.hypot(p[0] - s.cx, p[1] - s.cy) < s.radius * 0.55f) c = p;
            }
            if (c == null) c = new float[]{s.cx, s.cy};
            fillPass(w, r, s, start, c[0], c[1]);
        }
    }

    /** Extra scenery around a fixed-layout hex (home, ruins, citadel): a star, two asteroid fields and a planet. */
    private static void outskirts(World w, Random r, StarSystem s, int start) {
        float danger = s.danger * 800f;
        star(w, r, s, start);
        field(w, r, s, start, 6);
        field(w, r, s, start, 5);
        planet(w, r, s, start, GalaxyGenerator.pickType(r, danger), danger);
    }

    private static void fillPass(World w, Random r, StarSystem s, int start, float bcx, float bcy) {
        float danger = s.danger * 800f; // feeds native turret counts like distance from home did
        switch (s.archetype) {
            case HOME:
                GalaxyGenerator.homeSystem(w, r, s.cx, s.cy);
                break;
            case NURSERY:
                for (int i = 0, n = 4 + r.nextInt(3); i < n; i++) star(w, r, s, start);
                for (int i = 0, n = 1 + r.nextInt(2); i < n; i++) planet(w, r, s, start, GalaxyGenerator.pickType(r, danger), danger);
                field(w, r, s, start, 5);
                break;
            case BELT: {
                star(w, r, s, start);
                // asteroid bands sweeping across the hex
                double a0 = r.nextDouble() * Math.PI;
                for (int band = 0; band < 2; band++) {
                    double a = a0 + band * Math.PI / 2.5;
                    float off = (band - 0.5f) * 1400f;
                    for (int i = -2; i <= 2; i++) {
                        float d = i * 1150f;
                        float x = bcx + (float) (Math.cos(a) * d - Math.sin(a) * off);
                        float y = bcy + (float) (Math.sin(a) * d + Math.cos(a) * off);
                        if (s.inside(x, y, 500) && !near(w, start, x, y, 300)) GalaxyGenerator.asteroidField(w, r, x, y, 6 + r.nextInt(4));
                    }
                }
                for (int i = 0, n = 1 + r.nextInt(2); i < n; i++) planet(w, r, s, start, barren(r), danger);
                if (r.nextFloat() < BELT_GIANT_CHANCE) { // the prize of the belt: millions of metal in one rock
                    float[] p = spot(w, r, s, start, 900, 350);
                    if (p != null) GalaxyGenerator.giantAsteroid(w, r, p[0], p[1]);
                }
                break;
            }
            case GARDEN:
                star(w, r, s, start);
                for (int i = 0, n = 4 + r.nextInt(3); i < n; i++) planet(w, r, s, start, PlanetType.HABITABLE, danger);
                field(w, r, s, start, 6);
                break;
            case FORTRESS:
                for (int i = 0, n = 1 + r.nextInt(2); i < n; i++) star(w, r, s, start);
                for (int i = 0, n = 2 + r.nextInt(2); i < n; i++) planet(w, r, s, start, PlanetType.DEFENDED, danger + 2500);
                field(w, r, s, start, 6);
                break;
            case FRONTIER:
                star(w, r, s, start);
                for (int i = 0, n = 4 + r.nextInt(2); i < n; i++) planet(w, r, s, start, barren(r), danger);
                for (int i = 0, n = 1 + r.nextInt(2); i < n; i++) field(w, r, s, start, 5);
                break;
            case RUINS:
                GalaxyGenerator.addPlanet(w, r, s.cx, s.cy, PlanetType.RUINS, danger + 3000);
                GalaxyGenerator.addPlanet(w, r, s.cx - 950, s.cy + 600, PlanetType.DEFENDED, danger + 3000);
                GalaxyGenerator.addPlanet(w, r, s.cx + 950, s.cy + 600, PlanetType.DEFENDED, danger + 3000);
                GalaxyGenerator.addStar(w, r, s.cx, s.cy - 1100);
                field(w, r, s, start, 8);
                break;
            case NEBULA:
                for (int i = 0; i < 2; i++) star(w, r, s, start);
                for (int i = 0, n = 2 + r.nextInt(2); i < n; i++) planet(w, r, s, start, GalaxyGenerator.pickType(r, danger), danger);
                for (int i = 0; i < 2; i++) field(w, r, s, start, 5);
                break;
            case VOID:
                // the graveyard of an old battle: salvage that never drifts away
                for (int i = 0, n = 5 + r.nextInt(5); i < n; i++) {
                    float[] p = spot(w, r, s, start, 500, 250);
                    if (p == null) continue;
                    Body b = new Body(Body.Kind.WRECK, p[0], p[1], 14 + r.nextFloat() * 14);
                    b.metal = b.maxMetal = 300 + r.nextFloat() * 700;
                    b.life = 1e9f;
                    w.bodies.add(b);
                }
                field(w, r, s, start, 4);
                break;
            case HAVEN:
                star(w, r, s, start);
                for (int i = 0; i < 2; i++) field(w, r, s, start, 6);
                planet(w, r, s, start, barren(r), danger);
                break;
            case CITADEL: // the pirate capital sits in the middle; rich pickings around it
                GalaxyGenerator.addStar(w, r, s.cx - 1600, s.cy + 900);
                GalaxyGenerator.addStar(w, r, s.cx + 1600, s.cy + 900);
                for (int i = 0; i < 3; i++) field(w, r, s, start, 8);
                planet(w, r, s, start, PlanetType.DEFENDED, danger + 4000);
                planet(w, r, s, start, barren(r), danger);
                break;
            default:
                break;
        }
    }

    private static PlanetType barren(Random r) {
        PlanetType[] t = {PlanetType.VOLCANIC, PlanetType.ICE, PlanetType.GAS, PlanetType.CRYSTAL};
        return t[r.nextInt(t.length)];
    }

    /** True if a body added since index `from` (this hex) is within d of (x, y). */
    static boolean near(World w, int from, float x, float y, float d) {
        for (int i = from; i < w.bodies.size(); i++) {
            Body b = w.bodies.get(i);
            if (Math.hypot(b.x - x, b.y - y) < d + b.radius) return true;
        }
        StarSystem s = w.systemAt(x, y);
        for (Body g : s.gates) if (g != null && Math.hypot(g.x - x, g.y - y) < d + 250) return true;
        return false;
    }

    /** A free spot in s, `margin` inside its edge and `clear` away from this hex's bodies and gates. */
    static float[] spot(World w, Random r, StarSystem s, int from, float margin, float clear) {
        for (int i = 0; i < 60; i++) {
            float[] p = s.randomPoint(r, margin);
            if (!near(w, from, p[0], p[1], clear)) return p;
        }
        return null;
    }

    private static void star(World w, Random r, StarSystem s, int from) {
        float[] p = spot(w, r, s, from, 600, 700);
        if (p != null) GalaxyGenerator.addStar(w, r, p[0], p[1]);
    }

    private static void planet(World w, Random r, StarSystem s, int from, PlanetType t, float danger) {
        float[] p = spot(w, r, s, from, 550, 450);
        if (p != null) GalaxyGenerator.addPlanet(w, r, p[0], p[1], t, danger);
    }

    private static void field(World w, Random r, StarSystem s, int from, int n) {
        float[] p = spot(w, r, s, from, 600, 450);
        if (p != null) GalaxyGenerator.asteroidField(w, r, p[0], p[1], n);
    }
}

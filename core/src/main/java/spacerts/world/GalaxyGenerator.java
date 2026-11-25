package spacerts.world;

import spacerts.units.ShipClass;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Seeded procedural galaxy: home systems, scattered star systems, asteroid fields, guarded worlds. */
public final class GalaxyGenerator {
    private GalaxyGenerator() {}

    private static final String[] A = {"Ka", "Ve", "Tor", "Ix", "Lu", "Mar", "Ze", "Or", "Qua", "Sel", "Dra", "Ny", "Ul", "Bex", "Cy", "Har", "Ost", "Pri", "Rho", "Thal"};
    private static final String[] B = {"ra", "lon", "dis", "mir", "tha", "rex", "vos", "nia", "lus", "kar", "ion", "eth", "oda", "sur", "zan", "phe", "tis", "gon"};
    private static final String[] C = {"", "", "", " Prime", " II", " IV", " Major", " Minor", "-7", " Reach"};

    public static String name(Random r) {
        return A[r.nextInt(A.length)] + B[r.nextInt(B.length)] + C[r.nextInt(C.length)];
    }

    public static void generate(World w) {
        if (w.galaxy.hive) {
            HiveGenerator.generate(w);
            return;
        }
        Random r = new Random(w.seed);
        float S = Galaxy.CLASSIC_SIZE;
        float[][] corners = {{1700, 1700}, {S - 1700, S - 1700}, {S - 1700, 1700}, {1700, S - 1700}};
        int first = r.nextInt(4);
        List<float[]> starts = new ArrayList<>();
        starts.add(corners[first]);
        // second faction gets the opposite corner, the rest fill remaining corners
        int[] opposite = {1, 0, 3, 2};
        starts.add(corners[opposite[first]]);
        for (int i = 0; i < 4 && starts.size() < 4; i++)
            if (i != first && i != opposite[first]) starts.add(corners[i]);
        int majors = 1 + w.rivalCount;

        for (int i = 0; i < majors; i++) homeSystem(w, r, starts.get(i)[0], starts.get(i)[1]);

        // contested centre: ancient ruins flanked by fortified worlds
        float cx = S / 2, cy = S / 2;
        addStar(w, r, cx, cy - 950);
        addPlanet(w, r, cx, cy, spacerts.world.PlanetType.RUINS, 6000);
        addPlanet(w, r, cx - 900, cy + 500, PlanetType.DEFENDED, 6000);
        addPlanet(w, r, cx + 900, cy + 500, PlanetType.DEFENDED, 6000);
        asteroidField(w, r, cx, cy + 1100, 8);

        int systems = 0, attempts = 0;
        while (systems < 48 && attempts < 4000) {
            attempts++;
            float x = 700 + r.nextFloat() * (S - 1400), y = 700 + r.nextFloat() * (S - 1400);
            if (tooClose(w, x, y, 1000)) continue;
            float ds = nearestStart(starts, majors, x, y);
            if (ds < 2400) continue;
            makeSystem(w, r, x, y, ds);
            systems++;
        }
        // loose asteroid fields
        int fields = 0;
        attempts = 0;
        while (fields < 16 && attempts < 2000) {
            attempts++;
            float x = 600 + r.nextFloat() * (S - 1200), y = 600 + r.nextFloat() * (S - 1200);
            if (tooClose(w, x, y, 600) || nearestStart(starts, majors, x, y) < 1800) continue;
            asteroidField(w, r, x, y, 4 + r.nextInt(5));
            fields++;
        }
        // two giant asteroids out in the open (own random stream, so the rest of the map is unchanged)
        Random gr = new Random(w.seed ^ 0x61A57L);
        int giants = 0;
        for (int i = 0; i < 400 && giants < 2; i++) {
            float x = 1500 + gr.nextFloat() * (S - 3000), y = 1500 + gr.nextFloat() * (S - 3000);
            if (tooClose(w, x, y, 700) || nearestStart(starts, majors, x, y) < 3000) continue;
            giantAsteroid(w, gr, x, y);
            giants++;
        }
        for (Body b : w.bodies) if (b.kind == Body.Kind.ASTEROID) w.initialAsteroids++;

        // starting fleets
        for (int i = 0; i < majors; i++) startingFleet(w, w.factions.get(i), starts.get(i)[0], starts.get(i)[1]);
        finish(w);
    }

    /** Common end of generation: settle spawned units, index bodies, send harvesters out, first vision pass. */
    static void finish(World w) {
        w.flushPending();
        w.galaxy.indexBodies(w.bodies);
        for (Unit u : w.units) if (u.s.harvester) w.economy.autoAssign(u);
        w.computePopulation();
        w.updateVision();
        w.conquest.recount();
    }

    /** Mothership, harvesters, a scout and a Lancer squad for faction f at (sx, sy). */
    static void startingFleet(World w, Faction f, float sx, float sy) {
        f.energy = 700;
        f.metal = 600;
        w.spawn(ShipClass.MOTHERSHIP, f, sx, sy);
        for (int h = 0; h < 4; h++)
            w.spawn(ShipClass.HARVESTER, f, sx + 90 + h * 25, sy - 80);
        Unit wisp = w.spawn(ShipClass.WISP, f, sx - 100, sy + 90);
        wisp.anchorX = wisp.x;
        wisp.anchorY = wisp.y;
        for (int l = 0; l < 3; l++) w.spawn(ShipClass.LANCER, f, sx - 100 + l * 25, sy - 100);
        if (f == w.player && w.difficulty.playerBonus) { // Easy: a head start
            f.energy += 400;
            f.metal += 400;
            for (int l = 0; l < 3; l++) w.spawn(ShipClass.LANCER, f, sx - 100 + l * 25, sy - 130);
        }
    }

    private static float nearestStart(List<float[]> starts, int majors, float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < starts.size(); i++) best = Math.min(best, (float) Math.hypot(starts.get(i)[0] - x, starts.get(i)[1] - y));
        return best;
    }

    static void homeSystem(World w, Random r, float sx, float sy) {
        float a = (float) Math.atan2(w.systemAt(sx, sy).cy - sy, w.systemAt(sx, sy).cx - sx);
        addStar(w, r, sx + cos(a + 1.3f) * 700, sy + sin(a + 1.3f) * 700);
        asteroidField(w, r, sx + cos(a - 1.2f) * 750, sy + sin(a - 1.2f) * 750, 7);
        asteroidField(w, r, sx + cos(a + 2.6f) * 950, sy + sin(a + 2.6f) * 950, 4);
        addPlanet(w, r, sx + cos(a + 0.25f) * 1450, sy + sin(a + 0.25f) * 1450, PlanetType.HABITABLE, 0);
        PlanetType[] barren = {PlanetType.VOLCANIC, PlanetType.ICE, PlanetType.GAS, PlanetType.CRYSTAL};
        addPlanet(w, r, sx + cos(a - 0.45f) * 1650, sy + sin(a - 0.45f) * 1650, barren[r.nextInt(4)], 0);
        addPlanet(w, r, sx + cos(a + 0.9f) * 2500, sy + sin(a + 0.9f) * 2500, PlanetType.DEFENDED, 2500);
        addStar(w, r, sx + cos(a - 0.9f) * 2600, sy + sin(a - 0.9f) * 2600);
    }

    static void makeSystem(World w, Random r, float x, float y, float distStart) {
        boolean star = r.nextFloat() < 0.75f;
        if (star) addStar(w, r, x, y);
        int planets = 1 + r.nextInt(3);
        for (int i = 0; i < planets; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            float d = star ? 400 + r.nextFloat() * 500 : i * 380f + r.nextFloat() * 200;
            float px = x + (float) Math.cos(ang) * d, py = y + (float) Math.sin(ang) * d;
            if (!w.systemAt(x, y).inside(px, py, 300)) continue;
            if (tooClose(w, px, py, 260)) continue;
            addPlanet(w, r, px, py, pickType(r, distStart), distStart);
        }
        if (r.nextFloat() < 0.5f) {
            double ang = r.nextDouble() * Math.PI * 2;
            float fx = x + (float) Math.cos(ang) * 650, fy = y + (float) Math.sin(ang) * 650;
            if (!tooClose(w, fx, fy, 250)) asteroidField(w, r, fx, fy, 4 + r.nextInt(5));
        }
    }

    static PlanetType pickType(Random r, float dist) {
        int[] weights = {18, 16, 13, 13, 12, 11, dist > 3500 ? 5 : 0};
        PlanetType[] types = {PlanetType.HABITABLE, PlanetType.DEFENDED, PlanetType.VOLCANIC, PlanetType.ICE, PlanetType.GAS, PlanetType.CRYSTAL, PlanetType.RUINS};
        int total = 0;
        for (int wgt : weights) total += wgt;
        int roll = r.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return types[i];
        }
        return PlanetType.HABITABLE;
    }

    static boolean tooClose(World w, float x, float y, float d) {
        for (Body b : w.bodies) if (Math.hypot(b.x - x, b.y - y) < d + b.radius) return true;
        return false;
    }

    public static Body addStar(World w, Random r, float x, float y) {
        Body b = new Body(Body.Kind.STAR, x, y, 85 + r.nextFloat() * 55);
        b.name = name(r);
        float t = r.nextFloat();
        if (t < 0.4f) b.color.set(1f, 0.85f, 0.45f, 1f);
        else if (t < 0.65f) b.color.set(1f, 0.6f, 0.3f, 1f);
        else if (t < 0.85f) b.color.set(0.75f, 0.85f, 1f, 1f);
        else b.color.set(1f, 0.45f, 0.4f, 1f);
        w.bodies.add(b);
        return b;
    }

    public static Body addPlanet(World w, Random r, float x, float y, PlanetType type, float distStart) {
        float radius;
        switch (type) {
            case GAS: radius = 90 + r.nextFloat() * 30; break;
            case DEFENDED: radius = 70 + r.nextFloat() * 18; break;
            case HABITABLE: radius = 55 + r.nextFloat() * 20; break;
            case RUINS: radius = 65 + r.nextFloat() * 15; break;
            default: radius = 45 + r.nextFloat() * 25; break;
        }
        Body b = new Body(Body.Kind.PLANET, x, y, radius);
        b.type = type;
        b.name = name(r);
        float v = 0.85f + r.nextFloat() * 0.3f;
        b.color.set(Math.min(1, type.color.r * v), Math.min(1, type.color.g * v), Math.min(1, type.color.b * v), 1f);
        w.bodies.add(b);

        int turrets = 0;
        if (type == PlanetType.DEFENDED) {
            turrets = Math.max(2, Math.min(6, 2 + (int) (distStart / 2500f) + r.nextInt(2)));
            b.maxGuards = 2 + turrets / 2;
        } else if (type == PlanetType.RUINS) {
            turrets = 4 + r.nextInt(3);
            b.maxGuards = 4;
        }
        if (turrets > 0) {
            b.guardRadius = radius + 520;
            for (int t = 0; t < turrets; t++) {
                double a = Math.PI * 2 * t / turrets;
                Unit u = w.spawn(ShipClass.TURRET, w.natives, x + (float) Math.cos(a) * (radius + 45), y + (float) Math.sin(a) * (radius + 45));
                u.home = b;
                b.turrets.add(u);
            }
            for (int g = 0; g < b.maxGuards; g++) {
                double a = Math.PI * 2 * g / b.maxGuards + 0.4;
                Unit u = w.spawn(ShipClass.GUARD, w.natives, x + (float) Math.cos(a) * (radius + 110), y + (float) Math.sin(a) * (radius + 110));
                u.home = b;
                b.guards.add(u);
            }
        }
        return b;
    }

    public static void asteroidField(World w, Random r, float x, float y, int n) {
        for (int i = 0; i < n; i++) {
            float ax = x + (float) r.nextGaussian() * 170, ay = y + (float) r.nextGaussian() * 170;
            Body b = new Body(Body.Kind.ASTEROID, w.clampX(ax, ay), w.clampY(ax, ay), 12 + r.nextFloat() * 14);
            b.maxMetal = (500 + r.nextFloat() * 700) * ASTEROID_METAL_MULT;
            b.metal = b.maxMetal;
            float g = 0.45f + r.nextFloat() * 0.2f;
            b.color.set(g, g * 0.93f, g * 0.85f, 1f);
            w.bodies.add(b);
        }
    }

    /** Every asteroid holds 3x the metal it used to (1500-3600). */
    public static final float ASTEROID_METAL_MULT = 3f;
    /** Giant asteroids: huge, slow to drain, holding 1-5 million metal. */
    public static final double GIANT_MIN = 1_000_000, GIANT_MAX = 5_000_000;

    /** A giant asteroid holding millions of metal (a long-term prize to hold and mine for days). */
    public static Body giantAsteroid(World w, Random r, float x, float y) {
        Body b = new Body(Body.Kind.ASTEROID, w.clampX(x, y), w.clampY(x, y), 70 + r.nextFloat() * 40);
        b.giant = true;
        b.maxMetal = GIANT_MIN + r.nextDouble() * (GIANT_MAX - GIANT_MIN);
        b.metal = b.maxMetal;
        b.name = "Giant " + name(r);
        float g = 0.5f + r.nextFloat() * 0.15f;
        b.color.set(g * 1.05f, g * 0.95f, g * 0.8f, 1f);
        w.bodies.add(b);
        return b;
    }

    private static float cos(float a) { return (float) Math.cos(a); }
    private static float sin(float a) { return (float) Math.sin(a); }
}
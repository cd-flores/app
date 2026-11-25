package spacerts.world;

import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pirate strongholds of the pirates-only Hive mode. Each base keeps a garrison on station that it slowly
 * rebuilds, raids launch from the bases, and when a base falls a new one rises somewhere else later, forever.
 */
public final class PirateBases {
    private PirateBases() {}

    /** Seconds between garrison reinforcements, and before a new stronghold rises. */
    public static final float GARRISON_REBUILD = 35f, RESPAWN_DELAY = 480f;
    /** Garrison ships engage intruders this close to their base and are pulled back beyond LEASH. */
    public static final float GUARD_RANGE = 1400f, LEASH = 1800f;
    public static final int START_GARRISON = 6;
    /** Raids may start once the player has had this long to set up. */
    public static final float GRACE = 360f;

    /** Ships on station at the Pirate Citadel from the start. */
    public static final int CITADEL_GARRISON = 16;
    /** Patrol fleets hold their post: engage intruders within PATROL_RANGE of it, pulled back beyond PATROL_LEASH. */
    public static final float PATROL_RANGE = 1400f, PATROL_LEASH = 2000f;

    /** Places the starting strongholds (one in every Pirate Haven), the Citadel at the top, and patrol fleets everywhere. */
    public static void seedStart(World w, Random r) {
        for (StarSystem s : w.galaxy.systems)
            if (s.archetype == SystemArchetype.HAVEN) spawnBase(w, r, s);
        StarSystem top = w.galaxy.citadel();
        if (top != null) {
            Unit citadel = spawnBase(w, r, top);
            citadel.tier = 3; // 3x hull, extra guns, big garrison
            citadel.hp = citadel.maxHp();
            citadel.shield = citadel.maxShield();
            for (int i = START_GARRISON; i < CITADEL_GARRISON; i++) reinforce(w, citadel, i);
        }
        for (StarSystem s : w.galaxy.systems) seedPatrols(w, r, s);
        w.pirateRespawn = RESPAWN_DELAY;
        w.threats.nextWave = Math.max(w.threats.nextWave, GRACE);
    }

    public static final int PATROL_FLEET_MULT = 2;

    /** Pirate patrol fleets holding a post in s; more and heavier the higher the hex's danger. */
    static void seedPatrols(World w, Random r, StarSystem s) {
        int d = s.danger;
        if (d == 0) return;
        int fleets = (d <= 2 ? 1 : d <= 5 ? 1 + r.nextInt(2) : 2) * PATROL_FLEET_MULT; // hexes are huge: twice the fleets, spread out
        for (int f = 0; f < fleets; f++) {
            float[] p = s.randomPoint(r, 900);
            List<ShipClass> ships = new ArrayList<>();
            if (d <= 2) for (int i = 0; i < 3; i++) ships.add(ShipClass.RAIDER);
            else if (d <= 5) {
                int n = 4 + r.nextInt(3);
                for (int i = 0; i < n; i++) ships.add(r.nextFloat() < 0.3f ? ShipClass.GUNSHIP : ShipClass.RAIDER);
            } else {
                int n = 3 + d / 2 + r.nextInt(2); // fewer but heavier ships the deeper you go
                for (int i = 0; i < n; i++) ships.add(r.nextFloat() < 0.3f + d * 0.04f ? ShipClass.GUNSHIP : ShipClass.RAIDER);
                if (d >= 9 && f == 0) ships.add(ShipClass.DREADNOUGHT);
            }
            for (int i = 0; i < ships.size(); i++) {
                double a = r.nextDouble() * Math.PI * 2;
                float rr = 60 + r.nextFloat() * 160;
                Unit u = w.spawn(ships.get(i), w.pirates, p[0] + (float) Math.cos(a) * rr, p[1] + (float) Math.sin(a) * rr);
                u.hasPost = true;
                u.postX = p[0];
                u.postY = p[1];
            }
        }
    }

    /** Pirate patrol ships currently holding a post in system s. */
    public static int patrolsIn(World w, StarSystem s) {
        int n = 0;
        for (Unit u : w.units) if (!u.dead && u.hasPost && w.systemAt(u.x, u.y) == s) n++;
        return n;
    }

    /** Builds a stronghold near the middle of s with a starting garrison. Returns the base or null. */
    public static Unit spawnBase(World w, Random r, StarSystem s) {
        float[] p = null;
        for (int i = 0; i < 80 && p == null; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            float d = r.nextFloat() * 1200f;
            float x = s.cx + (float) Math.cos(a) * d, y = s.cy + (float) Math.sin(a) * d;
            if (s.inside(x, y, 700) && !GalaxyGenerator.tooClose(w, x, y, 260)) p = new float[]{x, y};
        }
        if (p == null) p = new float[]{s.cx, s.cy};
        Unit base = w.spawn(ShipClass.PIRATE_BASE, w.pirates, p[0], p[1]);
        for (int i = 0; i < START_GARRISON; i++) reinforce(w, base, i);
        return base;
    }

    /** Maximum garrison per base: grows with the raid count (the Citadel keeps a bigger one). */
    public static int garrisonMax(World w) {
        return Math.min(20, START_GARRISON + Math.min(w.threats.wave, 30) / 2);
    }

    private static int garrisonMax(World w, Unit base) {
        return base.tier >= 3 ? Math.max(CITADEL_GARRISON, garrisonMax(w)) + 4 : garrisonMax(w);
    }

    /** How many strongholds the pirates want: grows with how far up the hive the player has pushed. */
    public static int targetBases(World w) {
        return Math.min(12, HiveGenerator.START_HAVENS + spacerts.systems.ConquestSystem.frontier(w) / 2);
    }

    private static void reinforce(World w, Unit base, int index) {
        ShipClass c = ShipClass.RAIDER;
        int danger = w.systemAt(base.x, base.y).danger; // deeper strongholds keep heavier garrisons
        if (danger >= 8 && !hasDreadnought(w, base)) c = ShipClass.DREADNOUGHT;
        else if (danger >= 5 && w.rng.nextFloat() < 0.4f) c = ShipClass.GUNSHIP;
        else if (w.rng.nextFloat() < 0.15f) c = ShipClass.GUNSHIP;
        double a = w.rng.nextDouble() * Math.PI * 2;
        float d = base.radius + 140 + (index % 4) * 40;
        Unit g = w.spawn(c, w.pirates, base.x + (float) Math.cos(a) * d, base.y + (float) Math.sin(a) * d);
        g.station = base;
        g.anchorX = g.x;
        g.anchorY = g.y;
    }

    private static boolean hasDreadnought(World w, Unit base) {
        for (Unit u : w.units) if (!u.dead && u.station == base && u.cls == ShipClass.DREADNOUGHT) return true;
        return false;
    }

    public static List<Unit> livingBases(World w) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : w.units) if (!u.dead && u.cls == ShipClass.PIRATE_BASE) out.add(u);
        return out;
    }

    public static int garrisonSize(World w, Unit base) {
        int n = 0;
        for (Unit u : w.units) if (!u.dead && u.station == base) n++;
        return n;
    }

    // ------------------------------------------------------------------ per tick

    /** Garrison rebuilding and stronghold respawning. Called every tick in pirates-only games. */
    public static void update(World w, float dt) {
        List<Unit> bases = livingBases(w);
        for (Unit b : bases) {
            b.work += dt;
            if (b.work < GARRISON_REBUILD) continue;
            b.work = 0;
            int n = garrisonSize(w, b);
            if (n < garrisonMax(w, b)) reinforce(w, b, n);
        }
        int strongholds = 0; // the Citadel doesn't count toward the stronghold target
        for (Unit b : bases) if (b.tier < 3) strongholds++;
        if (!w.ascended && strongholds < targetBases(w)) {
            w.pirateRespawn -= dt;
            if (w.pirateRespawn <= 0) {
                w.pirateRespawn = RESPAWN_DELAY;
                StarSystem s = respawnSystem(w, bases);
                if (s != null) {
                    s.archetype = SystemArchetype.HAVEN;
                    spawnBase(w, w.rng, s);
                    w.announce("A new pirate stronghold has risen in " + s.name + ".", World.ALERT);
                }
            }
        }
    }

    /**
     * Where a new stronghold may rise: an unclaimed hex above the player's frontier, 3+ jumps from every player
     * colony / mothership, not owned or next to an owned hex, with no living base; the most dangerous ones first.
     */
    static StarSystem respawnSystem(World w, List<Unit> bases) {
        List<StarSystem> mine = new ArrayList<>();
        for (Unit u : w.units)
            if (!u.dead && u.owner == w.player && (u.cls == ShipClass.COLONY || u.cls == ShipClass.MOTHERSHIP)) mine.add(w.systemAt(u.x, u.y));
        int frontier = spacerts.systems.ConquestSystem.frontier(w);
        List<StarSystem> ok = new ArrayList<>();
        for (StarSystem s : w.galaxy.systems) {
            if (s.archetype == SystemArchetype.HOME || s.archetype == SystemArchetype.CITADEL || s.owned() || s.danger <= frontier) continue;
            boolean far = true;
            for (StarSystem m : mine) if (w.galaxy.jumps(s, m) < 3) far = false;
            for (StarSystem nb : s.neighbours) if (nb != null && nb.owned()) far = false;
            for (Unit b : bases) if (w.systemAt(b.x, b.y) == s) far = false;
            if (far) ok.add(s);
        }
        if (ok.isEmpty()) return null;
        ok.sort((a, b) -> Integer.compare(b.danger, a.danger));
        int pool = Math.max(1, ok.size() / 3); // pick among the most dangerous third
        return ok.get(w.rng.nextInt(pool));
    }

    /** Test hook: where the next stronghold could rise right now. */
    public static StarSystem respawnSystemForTest(World w) {
        return respawnSystem(w, livingBases(w));
    }

    /** The base a raid on `target` launches from: usually the closest by jumps, sometimes a random one. */
    public static Unit raidSource(World w, List<Unit> bases, Unit target) {
        if (bases.isEmpty()) return null;
        if (w.rng.nextFloat() < 0.4f) return bases.get(w.rng.nextInt(bases.size()));
        StarSystem ts = w.systemAt(target.x, target.y);
        Unit best = bases.get(0);
        int bj = Integer.MAX_VALUE;
        for (Unit b : bases) {
            int j = w.galaxy.jumps(w.systemAt(b.x, b.y), ts);
            if (j < bj) { bj = j; best = b; }
        }
        return best;
    }

    /**
     * Garrison behaviour (every couple of seconds): guard the base, chase intruders inside GUARD_RANGE, come
     * back when dragged past the LEASH, and drift around the station when idle. Returns true if u is a garrison ship.
     */
    public static boolean manageGarrison(World w, Unit u) {
        Unit base = u.station;
        float ax, ay, range, leash, orbit;
        if (base != null) {
            if (base.dead) { // orphaned: become a raider
                u.station = null;
                u.idle();
                return false;
            }
            ax = base.x;
            ay = base.y;
            range = GUARD_RANGE;
            leash = LEASH;
            orbit = base.radius + 150;
        } else if (u.hasPost) { // patrol fleet holding a point in its hex
            ax = u.postX;
            ay = u.postY;
            range = PATROL_RANGE;
            leash = PATROL_LEASH;
            orbit = 60;
        } else return false;
        float fromPost = u.dist(ax, ay);
        if (fromPost > leash) {
            u.command(Order.MOVE, ax + (w.rng.nextFloat() - 0.5f) * 300, ay + (w.rng.nextFloat() - 0.5f) * 300);
            u.target = null;
            return true;
        }
        if (u.target != null && !u.target.dead) return true;
        Unit intruder = null;
        float bd = range;
        w.hash.query(ax, ay, range + 60, near);
        for (Unit e : near) {
            if (e.dead || !e.owner.isMajor() || e.s.internal) continue;
            float d = e.dist(ax, ay);
            if (d < bd) { bd = d; intruder = e; }
        }
        if (intruder != null) {
            u.command(Order.ATTACK_MOVE, intruder.x, intruder.y);
            return true;
        }
        if (u.order == Order.IDLE && (fromPost > 600 || w.rng.nextFloat() < 0.15f)) { // lazy patrol around the post
            double a = w.rng.nextDouble() * Math.PI * 2;
            float d = orbit + w.rng.nextFloat() * 300;
            u.command(Order.MOVE, ax + (float) Math.cos(a) * d, ay + (float) Math.sin(a) * d);
        }
        return true;
    }

    private static final List<Unit> near = new ArrayList<>();

    /** A raider with nothing left to hit flies home: it joins the nearest base's garrison, or despawns there. */
    public static boolean sendHome(World w, Unit p) {
        List<Unit> bases = livingBases(w);
        if (bases.isEmpty()) return false;
        StarSystem here = w.systemAt(p.x, p.y);
        Unit best = null;
        int bj = Integer.MAX_VALUE;
        for (Unit b : bases) {
            int j = w.galaxy.jumps(here, w.systemAt(b.x, b.y));
            if (j < bj) { bj = j; best = b; }
        }
        if (garrisonSize(w, best) < garrisonMax(w, best)) {
            p.station = best;
            p.command(Order.MOVE, best.x + (w.rng.nextFloat() - 0.5f) * 300, best.y + (w.rng.nextFloat() - 0.5f) * 300);
        } else {
            p.command(Order.MOVE, best.x, best.y);
            p.leaving = true;
        }
        return true;
    }

    /** Called when a stronghold is destroyed: the garrison turns on the player and the respawn clock restarts. */
    public static void onBaseDestroyed(World w, Unit base) {
        for (Unit u : w.units)
            if (!u.dead && u.station == base) {
                u.station = null;
                u.idle();
            }
        w.pirateRespawn = Math.max(w.pirateRespawn, RESPAWN_DELAY);
        StarSystem s = w.systemAt(base.x, base.y);
        if (base.tier >= 3)
            w.announce("THE PIRATE CITADEL HAS FALLEN! Claim its hex and build the Ascension Gate there to end the pirate clans.", World.GOOD);
        else w.announce("Pirate stronghold in " + s.name + " destroyed! Raids grow weaker - for now.", World.GOOD);
    }
}

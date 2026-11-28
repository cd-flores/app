package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.net.Lockstep;
import spacerts.save.SaveManager;
import spacerts.systems.TacticsSystem;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import static org.junit.jupiter.api.Assertions.*;

/** Lancer buff, the Pyre (burn damage, anti-swarm), colony buoys, and bots without auto-retreat. */
class UnitsTest {

    private static World make() {
        World w = new World(GameSetup.skirmish(91, 1, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        return w;
    }

    private static void run(World w, float seconds) {
        int n = (int) Math.ceil(seconds / Lockstep.STEP);
        for (int i = 0; i < n; i++) w.update(Lockstep.STEP);
    }

    private static Unit spawn(World w, ShipClass c, Faction f, float x, float y) {
        Unit u = w.spawn(c, f, x, y);
        w.flushPending();
        return u;
    }

    private static Faction rival(World w) {
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) return f;
        throw new IllegalStateException();
    }

    /** Removes pirates and natives so a test fight is just the two empires. */
    private static void clearField(World w) {
        for (Unit u : w.units) if (u.owner.kind == Faction.Kind.PIRATES || u.owner.kind == Faction.Kind.NATIVES) u.kill(true);
        run(w, 0.1f);
    }

    @Test
    void lancersHaveTripleHullAndDoubleDamage() {
        assertEquals(330, ShipClass.LANCER.s.hp, 0.01);
        assertEquals(14, ShipClass.LANCER.s.damage, 0.01);
    }

    @Test
    void pyreIsAPricierStationTwoShip() {
        assertTrue(ShipClass.PYRE.s.energy > ShipClass.HIVE.s.energy && ShipClass.PYRE.s.metal > ShipClass.HIVE.s.metal, "dearer than the Hive");
        assertEquals(2, ShipClass.PYRE.s.factoryTier);
        assertTrue(ShipClass.PYRE.s.splash > 0 && ShipClass.PYRE.s.range <= 250, "short-range splash");
        assertTrue(ShipClass.PYRE.s.burnTime > 0 && ShipClass.PYRE.s.burnDps > 0);
        assertEquals(TacticsSystem.Role.INFANTRY, TacticsSystem.role(ShipClass.PYRE));
        World w = make();
        Faction p = w.player;
        Unit st = spawn(w, ShipClass.FACTORY, p, p.mothership.x + 400, p.mothership.y);
        String why = w.production.blocker(st, ShipClass.PYRE);
        assertNotNull(why, "tier I station can't build it");
        st.tier = 2;
        assertNull(w.production.blocker(st, ShipClass.PYRE), "tier II can");
    }

    @Test
    void pyreHitsSetShipsBurning() {
        World w = make();
        clearField(w);
        Faction p = w.player, r = rival(w);
        float x = Galaxy.CLASSIC_SIZE / 2, y = Galaxy.CLASSIC_SIZE * 0.35f;
        Unit pyre = spawn(w, ShipClass.PYRE, p, x, y);
        Unit t = spawn(w, ShipClass.BULWARK, r, x + 150, y);
        t.shield = 0;
        w.combat.hit(pyre, t, 1f);
        assertTrue(t.burnTime > 0, "burning");
        assertTrue(t.burnDps > 0);
        pyre.kill(true); // only the fire keeps hurting it now
        t.retreatOff = true;
        float hp0 = t.hp + t.shield;
        run(w, 2);
        assertTrue(t.hp + t.shield < hp0, "the fire keeps burning after the shot");
        run(w, ShipClass.PYRE.s.burnTime);
        assertEquals(0, t.burnTime, 0.2f, "burns out");
        float hp1 = t.hp + t.shield;
        assertTrue(hp1 > 0);
    }

    @Test
    void pyreSplashBurnsNeighbours() {
        World w = make();
        clearField(w);
        Faction p = w.player, r = rival(w);
        float x = Galaxy.CLASSIC_SIZE / 2, y = Galaxy.CLASSIC_SIZE * 0.35f;
        Unit pyre = spawn(w, ShipClass.PYRE, p, x, y);
        pyre.retreatOff = true;
        Unit a = spawn(w, ShipClass.BULWARK, r, x + 170, y);
        Unit b = spawn(w, ShipClass.BULWARK, r, x + 170, y + 40);
        for (Unit u : new Unit[]{a, b}) { u.hp = 1e7f; u.retreatOff = true; }
        boolean both = false;
        for (int i = 0; i < (int) (6 / Lockstep.STEP) && !both; i++) {
            w.update(Lockstep.STEP);
            both = a.burnTime > 0 && b.burnTime > 0;
        }
        assertTrue(both, "the flamer's splash set both on fire");
    }

    @Test
    void pyreShredsTheHiveSwarm() {
        World w = make();
        clearField(w);
        Faction p = w.player, r = rival(w);
        float x = Galaxy.CLASSIC_SIZE / 2, y = Galaxy.CLASSIC_SIZE * 0.35f;
        Unit pyre = spawn(w, ShipClass.PYRE, p, x, y);
        Unit drone = spawn(w, ShipClass.DRONE, r, x + 100, y);
        drone.hp = 1e6f;
        drone.shield = 0;
        float before = drone.hp;
        w.combat.hit(pyre, drone, 1f);
        float dealt = before - drone.hp;
        float plain = pyre.damage() * (1f - ShipClass.DRONE.s.armor);
        assertEquals(plain * 4f, dealt, plain * 0.05f, "4x damage against Hive drones");
    }

    @Test
    void coloniesLaunchGrowingRingsOfBuoys() {
        World w = make();
        Faction p = w.player;
        Body home = null;
        for (Body b : w.bodies)
            if (b.colonizable() && (home == null || p.mothership.dist(b.x, b.y) < p.mothership.dist(home.x, home.y))) home = b;
        assertNotNull(home);
        w.colonize(home, p);
        w.flushPending();
        w.updateVision();
        assertEquals(0, home.buoyRings, "none at first");

        // a point on the outermost ring, dark before any buoys
        float[] far = new float[2];
        int dark = -1;
        for (int j = 0; j < Body.BUOYS_PER_RING && dark < 0; j++) {
            home.buoy(Body.BUOY_MAX_RINGS, j, far);
            if (!p.vision.isVisible(far[0], far[1])) dark = j;
        }
        assertTrue(dark >= 0, "some outer-ring spot starts in the dark");

        home.buoyTimer = Body.BUOY_PERIOD - 0.5f;
        run(w, 1);
        assertEquals(1, home.buoyRings, "first ring after 5 minutes");
        for (int k = 2; k <= Body.BUOY_MAX_RINGS + 2; k++) {
            home.buoyTimer = Body.BUOY_PERIOD - 0.5f;
            run(w, 1);
        }
        assertEquals(Body.BUOY_MAX_RINGS, home.buoyRings, "stops at 5 rings");
        home.buoy(Body.BUOY_MAX_RINGS, dark, far);
        assertTrue(p.vision.isVisible(far[0], far[1]), "the outer ring lights up the dark");
        float[] ring1 = new float[2];
        home.buoy(1, 0, ring1);
        assertEquals(home.radius + Body.BUOY_SPACING, Math.hypot(ring1[0] - home.x, ring1[1] - home.y), 1, "ring 1 sits 250 out");

        World back = SaveManager.fromJson(SaveManager.toJson(w));
        Body copy = null;
        for (Body b : back.bodies) if (b.name.equals(home.name) && Math.abs(b.x - home.x) < 1) copy = b;
        assertNotNull(copy);
        assertEquals(Body.BUOY_MAX_RINGS, copy.buoyRings, "saved and loaded");

        home.colony.kill(false);
        run(w, 0.5f);
        assertEquals(0, home.buoyRings, "a lost colony loses its buoys");
    }

    @Test
    void economyStructuresAreFiveTimesTougher() {
        assertEquals(1750, ShipClass.OUTPOST.s.hp, 0.01, "Research Outpost 350 -> 1750");
        assertEquals(4500, ShipClass.SOLAR.s.hp, 0.01, "Solar Collector 900 -> 4500");
        assertEquals(5000, ShipClass.MINER.s.hp, 0.01, "Asteroid Miner 1000 -> 5000");
    }

    @Test
    void theMatchEndsWhenTheLastEnemyEmpireFalls() {
        World w = new World(GameSetup.skirmish(91, 2, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        java.util.List<Faction> rivals = new java.util.ArrayList<>();
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) rivals.add(f);
        assertEquals(2, rivals.size());

        rivals.get(0).mothership.kill(false);
        run(w, 0.5f);
        assertTrue(rivals.get(0).eliminated);
        assertFalse(w.matchOver, "one enemy is still standing");

        rivals.get(1).mothership.kill(false);
        run(w, 0.5f);
        assertTrue(w.matchOver, "the last enemy fell");
        assertSame(w.player, w.winner);
        assertFalse(w.gameOver, "that's a win, not a defeat");
        float t = w.time;
        run(w, 2);
        assertEquals(t, w.time, 1e-6, "the match is frozen");

        World back = SaveManager.fromJson(SaveManager.toJson(w));
        assertTrue(back.matchOver, "saved as finished");
        assertNotNull(back.winner);
        assertEquals(w.player.id, back.winner.id);
    }

    @Test
    void botsDontAutoRetreatButPlayersDo() {
        World w = make();
        clearField(w);
        Faction p = w.player, r = rival(w);
        float x = Galaxy.CLASSIC_SIZE / 2, y = Galaxy.CLASSIC_SIZE * 0.35f;
        Unit mine = spawn(w, ShipClass.BULWARK, p, x, y);
        Unit theirs = spawn(w, ShipClass.BULWARK, r, x + 3000, y);
        for (Unit u : new Unit[]{mine, theirs}) {
            u.hp = u.maxHp() * 0.1f;
            u.lastHit = w.time;
        }
        run(w, 2);
        assertTrue(mine.retreating, "player ship pulls back below 25%");
        assertFalse(theirs.retreating, "bot ship fights on");
    }
}

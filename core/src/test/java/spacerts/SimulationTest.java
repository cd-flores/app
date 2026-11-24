package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.research.Tech;
import spacerts.save.SaveManager;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.PlanetType;
import spacerts.world.World;

import static org.junit.jupiter.api.Assertions.*;

/* Headless smoke tests: the simulation runs without a window. */
class SimulationTest {

    private static World newWorld(long seed, int rivals) {
        World w = new World(seed, rivals);
        GalaxyGenerator.generate(w);
        return w;
    }

    private static void run(World w, float seconds) {
        float step = 1f / 30f;
        int n = (int) (seconds / step);
        for (int i = 0; i < n; i++) w.update(step);
    }

    @Test
    void galaxyHasEverythingTheDesignNeeds() {
        World w = newWorld(42, 3);
        int stars = 0, asteroids = 0, habitable = 0, defended = 0, barren = 0, ruins = 0;
        for (Body b : w.bodies) {
            if (b.kind == Body.Kind.STAR) stars++;
            else if (b.kind == Body.Kind.ASTEROID) asteroids++;
            else if (b.type == PlanetType.HABITABLE) habitable++;
            else if (b.type == PlanetType.DEFENDED) { defended++; assertTrue(b.guarded()); }
            else if (b.type == PlanetType.RUINS) { ruins++; assertTrue(b.guarded()); }
            else barren++;
        }
        System.out.printf("stars=%d asteroids=%d habitable=%d defended=%d barren=%d ruins=%d units=%d%n",
                stars, asteroids, habitable, defended, barren, ruins, w.units.size());
        assertTrue(stars > 20 && asteroids > 60 && habitable > 5 && defended > 5 && barren > 5 && ruins >= 1);
        for (int i = 0; i < 4; i++) assertNotNull(w.factions.get(i).mothership);
    }

    @Test
    void harvestersGatherBothResources() {
        World w = newWorld(7, 1);
        Faction p = w.player;
        float e0 = p.energy, m0 = p.metal;
        run(w, 90);
        System.out.printf("after 90s: energy %.0f -> %.0f, metal %.0f -> %.0f%n", e0, p.energy, m0, p.metal);
        assertTrue(p.energy > e0 + 50, "energy should grow");
        assertTrue(p.metal > m0 + 50, "metal should grow");
    }

    @Test
    void defendedPlanetMustBeConqueredBeforeColonizing() {
        World w = newWorld(11, 1);
        Faction p = w.player;
        Body fort = null;
        for (Body b : w.bodies) if (b.type == PlanetType.DEFENDED) { fort = b; break; }
        assertNotNull(fort);
        assertFalse(fort.colonizable(), "guarded world must not be colonizable");
        for (Unit t : fort.turrets) t.kill(false);
        run(w, 0.1f);
        assertFalse(fort.guarded());
        assertTrue(fort.colonizable(), "cleared world becomes colonizable");
        w.colonize(fort, p);
        w.flushPending();
        w.computePopulation();
        assertEquals(15 + 25, p.popCap, "fortified world gives +25 housing");
    }

    @Test
    void researchHubOutpostsAndRareShips() {
        World w = newWorld(5, 1);
        Faction p = w.player;
        p.energy = 100000;
        p.metal = 100000;
        Unit fac = buildFactory(w, p);
        assertNotNull(w.production.blocker(fac, ShipClass.LEVIATHAN), "Leviathan locked at start");
        Unit h = harvesters(w, p).get(0);
        float[] site = site(w, p, p.mothership, ShipClass.HUB.s.radius);
        assertNull(w.construction.order(h, ShipClass.HUB, null, site[0], site[1]));
        Unit h2 = harvesters(w, p).get(1);
        float[] site2 = site(w, p, p.mothership, ShipClass.HUB.s.radius);
        assertNotNull(w.construction.order(h2, ShipClass.HUB, null, site2[0], site2[1]), "only one hub");
        run(w, 70);
        assertNotNull(p.hub, "hub built by a harvester");
        // surveyor to nearest barren planet
        assertNull(w.production.enqueue(p.hub, ShipClass.SURVEYOR));
        run(w, 14);
        Unit surveyor = null;
        for (Unit u : w.units) if (u.owner == p && u.cls == ShipClass.SURVEYOR) surveyor = u;
        assertNotNull(surveyor);
        Body target = null;
        float best = Float.MAX_VALUE;
        for (Body b : w.bodies)
            if (b.canSurvey() && surveyor.dist(b.x, b.y) < best) { best = surveyor.dist(b.x, b.y); target = b; }
        surveyor.command(spacerts.units.Order.SURVEY, target.x, target.y);
        surveyor.body = target;
        run(w, 120);
        assertNotNull(target.outpost, "outpost established");
        assertTrue(p.rare[target.type.rare.ordinal()] >= 1, "rare item extracted");

        // research path to Leviathan
        for (int i = 0; i < 5; i++) p.rare[i] = 50;
        assertNull(w.production.startResearch(p, Tech.HUB_TIER_2));
        run(w, 61);
        assertTrue(p.has(Tech.HUB_TIER_2));
        assertNull(w.production.startResearch(p, Tech.HUB_TIER_3));
        run(w, 91);
        assertNull(w.production.startResearch(p, Tech.LEVIATHAN_TECH));
        run(w, 121);
        assertTrue(p.has(Tech.LEVIATHAN_TECH));
        // need housing for a 20-pop ship
        for (Body b : w.bodies) if (b.colonizable() && p.popCap < 60) { w.colonize(b, p); w.flushPending(); w.computePopulation(); }
        assertNull(w.production.enqueue(fac, ShipClass.LEVIATHAN), "Leviathan buildable after research");
        assertNotNull(w.production.enqueue(fac, ShipClass.LEVIATHAN), "Leviathan cap 1");
    }

    // ------------------------------------------------------------------ helpers for the construction tests

    private static java.util.List<Unit> harvesters(World w, Faction p) {
        java.util.List<Unit> list = new java.util.ArrayList<>();
        for (Unit u : w.units) if (!u.dead && u.owner == p && u.s.harvester) list.add(u);
        return list;
    }

    /** A valid open spot near a unit for a structure of radius r. */
    private static float[] site(World w, Faction p, Unit near, float r) {
        for (int i = 0; i < 400; i++) {
            float a = i * 0.7f, d = near.radius + 150 + (i % 10) * 40;
            float x = near.x + (float) Math.cos(a) * d, y = near.y + (float) Math.sin(a) * d;
            if (w.construction.siteBlocker(p, x, y, r) == null) {
                boolean taken = false;
                for (Unit u : w.units)
                    if (u.buildCls != null && Math.hypot(u.buildX - x, u.buildY - y) < 200) taken = true;
                if (!taken) return new float[]{x, y};
            }
        }
        throw new AssertionError("no site found");
    }

    /** Has a harvester build a Ship Factory next to the mothership and returns it. */
    private static Unit buildFactory(World w, Faction p) {
        Unit h = harvesters(w, p).get(harvesters(w, p).size() - 1);
        float[] s = site(w, p, p.mothership, ShipClass.FACTORY.s.radius);
        assertNull(w.construction.order(h, ShipClass.FACTORY, null, s[0], s[1]));
        for (int i = 0; i < 90 * 30; i++) {
            w.update(1f / 30f);
            for (Unit u : w.units) if (!u.dead && u.owner == p && u.cls == ShipClass.FACTORY) return u;
        }
        throw new AssertionError("factory never finished");
    }

    private static Body colonizeNearest(World w, Faction p) {
        Body best = null;
        for (Body b : w.bodies)
            if (b.colonizable() && (best == null || p.mothership.dist(b.x, b.y) < p.mothership.dist(best.x, best.y))) best = b;
        assertNotNull(best);
        w.colonize(best, p);
        w.flushPending();
        w.computePopulation();
        w.updateVision();
        return best;
    }

    // ------------------------------------------------------------------ new feature tests

    @Test
    void mothershipBuildsHarvestersAndFactoriesBuildWarships() {
        World w = newWorld(3, 1);
        Faction p = w.player;
        Unit ms = p.mothership;
        assertNotNull(w.production.enqueue(ms, ShipClass.LANCER), "mothership no longer builds warships");
        assertNull(w.production.enqueue(ms, ShipClass.HARVESTER));
        assertEquals(700 - 50, p.energy, 0.01, "started with the bigger stockpile");
        Unit fac = buildFactory(w, p);
        assertNull(w.production.enqueue(fac, ShipClass.LANCER), "factory builds warships");
        assertNull(w.production.enqueue(fac, ShipClass.COLONY_SHIP), "factory builds colony ships");
        assertNotNull(w.production.enqueue(fac, ShipClass.HARVESTER), "harvesters only from the mothership");
    }

    @Test
    void constructionRefundsWhenStopped() {
        World w = newWorld(4, 1);
        Faction p = w.player;
        p.energy = 1000;
        p.metal = 1000;
        Unit h = harvesters(w, p).get(0);
        float[] s = site(w, p, p.mothership, ShipClass.FACTORY.s.radius);
        assertNull(w.construction.order(h, ShipClass.FACTORY, null, s[0], s[1]));
        assertEquals(700, p.energy, 0.01, "paid up front");
        run(w, 2);
        h.idle(); // X: stop
        assertEquals(700 + 300, p.energy, 5, "refunded on stop");
        assertNull(h.buildCls);
        // a harvester killed mid-build refunds too
        assertNull(w.construction.order(h, ShipClass.FACTORY, null, s[0], s[1]));
        float before = p.energy;
        h.kill(false);
        run(w, 0.1f);
        assertEquals(before + 300, p.energy, 5, "refunded on death");
    }

    @Test
    void planetUpgradesShareFivePoints() {
        World w = newWorld(8, 1);
        Faction p = w.player;
        p.energy = 9000;
        p.metal = 9000;
        Body b = colonizeNearest(w, p);
        Unit h = harvesters(w, p).get(0);
        float shield0 = b.colony.maxShield();
        // spend the 5 points: 3 on turrets, 2 on shield
        ShipClass[] plan = {ShipClass.DEF_TURRET, ShipClass.DEF_SHIELD, ShipClass.DEF_TURRET, ShipClass.DEF_SHIELD, ShipClass.DEF_TURRET};
        for (int k = 0; k < plan.length; k++) {
            assertNull(w.construction.order(h, plan[k], b, b.x, b.y), "point " + (k + 1));
            for (int i = 0; i < 120 * 30 && b.defensePoints() <= k; i++) w.update(1f / 30f);
            assertEquals(k + 1, b.defensePoints());
        }
        assertEquals(3, b.turretPoints);
        assertEquals(2, b.shieldPoints);
        int turrets = 0;
        for (Unit u : w.units) if (!u.dead && u.owner == p && u.cls == ShipClass.TURRET && u.home == b) turrets++;
        assertEquals(6, turrets, "2 turrets per turret point");
        assertEquals(shield0 + 2 * Body.SHIELD_PER_POINT, b.colony.maxShield(), 0.01, "+400 shield per shield point");
        assertNotNull(w.construction.order(h, ShipClass.DEF_SHIELD, b, b.x, b.y), "all 5 points used");
        assertNotNull(w.construction.order(h, ShipClass.DEF_TURRET, b, b.x, b.y), "all 5 points used");
        // only on your own colonies
        Body other = null;
        for (Body o : w.bodies) if (o.habitable() && !o.colonized()) other = o;
        assertNotNull(w.construction.order(h, ShipClass.DEF_TURRET, other, other.x, other.y));
    }

    @Test
    void allFivePointsCanGoToShield() {
        World w = newWorld(18, 1);
        Faction p = w.player;
        p.energy = 9000;
        p.metal = 9000;
        Body b = colonizeNearest(w, p);
        for (int k = 0; k < Body.MAX_DEFENSE; k++) w.construction.upgradePlanet(b, p, ShipClass.DEF_SHIELD);
        w.flushPending();
        assertEquals(Body.MAX_DEFENSE, b.shieldPoints);
        assertEquals(ShipClass.COLONY.s.shield + 5 * Body.SHIELD_PER_POINT, b.colony.maxShield(), 0.01);
        // the shield recharges faster after damage
        b.colony.shield = 0;
        b.colony.lastHit = w.time - 10;
        run(w, 1);
        assertTrue(b.colony.shield > (ShipClass.COLONY.s.shieldRegen + 5 * Body.REGEN_PER_POINT) * 0.9f, "faster recharge");
    }

    @Test
    void harvesterBuildsSlowAreaRepairMender() {
        World w = newWorld(9, 1);
        Faction p = w.player;
        p.energy = 2000;
        p.metal = 2000;
        Unit h = harvesters(w, p).get(0);
        assertNull(w.construction.order(h, ShipClass.MENDER, null, h.x, h.y));
        run(w, 20);
        Unit m = null;
        for (Unit u : w.units) if (!u.dead && u.owner == p && u.cls == ShipClass.MENDER) m = u;
        assertNotNull(m, "mender built");
        assertTrue(m.speed() <= 15, "crawling slow");
        assertEquals(6, ShipClass.MENDER.s.pop, "6 housing");
        // it heals several damaged allies at once
        Unit a = w.spawn(ShipClass.LANCER, p, m.x + 100, m.y);
        Unit c = w.spawn(ShipClass.BULWARK, p, m.x - 150, m.y);
        w.flushPending();
        a.hp = 20;
        c.hp = 100;
        run(w, 3);
        assertTrue(a.hp > 50 && c.hp > 130, "both repaired");
    }

    @Test
    void rallyFlagSendsNewWarshipsThere() {
        World w = newWorld(12, 1);
        Faction p = w.player;
        p.energy = 2000;
        p.metal = 2000;
        Unit fac = buildFactory(w, p);
        p.hasFlag = true;
        p.flagX = fac.x + 900;
        p.flagY = fac.y + 300;
        assertNull(w.production.enqueue(fac, ShipClass.LANCER));
        run(w, 25);
        int near = 0;
        for (Unit u : w.units)
            if (!u.dead && u.owner == p && u.cls == ShipClass.LANCER && u.dist(p.flagX, p.flagY) < 250) near++;
        assertEquals(3, near, "the new lancer squad gathered at the flag");
    }

    @Test
    void coloniesProduceResourcesWithoutHarvesters() {
        World w = newWorld(13, 1);
        Faction p = w.player;
        for (Unit h : harvesters(w, p)) h.kill(true);
        run(w, 0.1f);
        colonizeNearest(w, p);
        float e0 = p.energy, m0 = p.metal;
        run(w, 30);
        assertEquals(e0 + 30, p.energy, 3, "+1 energy/s");
        assertEquals(m0 + 30, p.metal, 3, "+1 metal/s");
    }

    @Test
    void shipsSpeedUpTowardOwnColonies() {
        World w = newWorld(14, 1);
        Faction p = w.player;
        Body col = colonizeNearest(w, p);
        Unit in = w.spawn(ShipClass.BULWARK, p, col.x + 1500, col.y);
        Unit out = w.spawn(ShipClass.BULWARK, p, col.x + 1500, col.y + 600);
        w.flushPending();
        in.command(spacerts.units.Order.MOVE, col.x, col.y);
        out.command(spacerts.units.Order.MOVE, col.x + 4000, col.y + 600);
        float ix = in.x, ox = out.x;
        run(w, 3);
        float dIn = Math.abs(in.x - ix), dOut = Math.abs(out.x - ox);
        System.out.printf("toward colony %.0f, away %.0f%n", dIn, dOut);
        assertTrue(dIn > dOut * 1.3f, "boosted toward own colony");
    }

    @Test
    void longGameStaysStableAndSaveLoadRoundTrips() {
        World w = newWorld(2026, 3);
        long t0 = System.currentTimeMillis();
        run(w, 15 * 60);
        int rivalColonies = 0; // rivals should expand on their own (measured mid-game: by minute 40 they may all be dead)
        for (Unit u : w.units) if (u.cls == ShipClass.COLONY && u.owner.kind == Faction.Kind.RIVAL) rivalColonies++;
        assertTrue(rivalColonies >= 3, "AI colonizes");
        run(w, 25 * 60); // 40 minutes of game time in total
        long ms = System.currentTimeMillis() - t0;
        int pirates = 0, total = w.units.size();
        for (Unit u : w.units) if (u.owner == w.pirates) pirates++;
        System.out.printf("40 min simulated in %d ms; units=%d pirates=%d waves=%d%n", ms, total, pirates, w.threats.wave);
        for (Faction f : w.factions) {
            if (!f.isMajor()) continue;
            int colonies = 0, outposts = 0, army = 0;
            for (Unit u : w.units) {
                if (u.owner != f) continue;
                if (u.cls == ShipClass.COLONY) colonies++;
                if (u.cls == ShipClass.OUTPOST) outposts++;
                if (u.cls.isWarship()) army++;
            }
            System.out.printf("  %-18s elim=%s E=%.0f M=%.0f pop=%d/%d colonies=%d outposts=%d army=%d techs=%d%n",
                    f.name, f.eliminated, f.energy, f.metal, f.popUsed, f.popCap, colonies, outposts, army, f.techs.size());
        }
        for (spacerts.world.Message m : w.messages) System.out.printf("  [%5.0fs] %s%n", m.time, m.text);
        assertTrue(w.threats.wave >= 5, "pirate waves keep coming");
        for (Unit u : w.units) {
            assertFalse(Float.isNaN(u.x) || Float.isNaN(u.y) || Float.isNaN(u.hp), "no NaN state");
            assertTrue(u.x >= 0 && u.y >= 0 && u.x <= World.SIZE && u.y <= World.SIZE, "units stay on the map");
        }
        w.player.hasFlag = true;
        w.player.flagX = 1234;
        w.player.flagY = 2345;
        Body defended = null;
        for (Body b : w.bodies) if (!b.dead && b.colonized()) { defended = b; break; }
        if (defended != null && defended.defensePoints() == 0) {
            defended.turretPoints = 2;
            defended.shieldPoints = 1;
        }
        String json = SaveManager.toJson(w);
        World l = SaveManager.fromJson(json);
        assertTrue(l.player.hasFlag);
        assertEquals(1234, l.player.flagX, 0.01);
        int lvA = 0, lvB = 0;
        for (Body b : w.bodies) if (!b.dead) lvA += b.turretPoints * 10 + b.shieldPoints;
        for (Body b : l.bodies) lvB += b.turretPoints * 10 + b.shieldPoints;
        assertEquals(lvA, lvB, "defense levels restored");
        assertEquals(w.units.size(), l.units.size());
        assertEquals(w.player.energy, l.player.energy, 0.01);
        assertEquals(w.player.techs, l.player.techs);
        assertEquals(w.time, l.time, 0.01);
        int colA = 0, colB = 0;
        for (Body b : w.bodies) if (!b.dead && b.colonized()) colA++;
        for (Body b : l.bodies) if (b.colonized()) colB++;
        assertEquals(colA, colB, "planet ownership restored");
        run(l, 60); // loaded world keeps running
        System.out.printf("save size %d KB%n", json.length() / 1024);
    }

    // ------------------------------------------------------------------ difficulty & automation

    private static World newWorld(long seed, int rivals, spacerts.world.Difficulty d) {
        World w = new World(seed, rivals, d);
        GalaxyGenerator.generate(w);
        return w;
    }

    @Test
    void difficultyScalesPiratesAndStart() {
        World easy = newWorld(21, 1, spacerts.world.Difficulty.EASY);
        World hard = newWorld(21, 1, spacerts.world.Difficulty.HARD);
        assertTrue(easy.threats.budget(5, 600, 2) < hard.threats.budget(5, 600, 2), "smaller pirate waves on Easy");
        assertTrue(easy.player.energy > hard.player.energy, "Easy head start");
        String json = SaveManager.toJson(hard);
        assertEquals(spacerts.world.Difficulty.HARD, SaveManager.fromJson(json).difficulty, "difficulty saved");
    }

    /** A 1-rival world with no native planets left and a rival army far above its attack threshold. */
    private static World readyToAttack(spacerts.world.Difficulty d, float minutes) {
        World w = newWorld(22, 1, d);
        for (Body b : w.bodies) for (Unit t : b.turrets) t.kill(true);
        Faction r = w.factions.get(1);
        for (int i = 0; i < 30; i++) w.spawn(ShipClass.BULWARK, r, r.mothership.x + 150 + (i % 6) * 40, r.mothership.y + (i / 6) * 40);
        w.flushPending();
        w.time = minutes * 60;
        return w;
    }

    private static boolean warned(World w) {
        for (spacerts.world.Message m : w.messages) if (m.text.contains("preparing to attack")) return true;
        return false;
    }

    @Test
    void rivalsAttackThePlayerLaterOnEasierDifficulties() {
        World easy = readyToAttack(spacerts.world.Difficulty.EASY, 13);
        World hard = readyToAttack(spacerts.world.Difficulty.HARD, 13);
        run(easy, 6);
        run(hard, 6);
        assertFalse(warned(easy), "Easy: no attack on empires at 13 minutes");
        assertTrue(warned(hard), "Hard: rivals strike from 12 minutes");
    }

    @Test
    void rivalAssaultIsAnnouncedBeforeItMoves() {
        World w = readyToAttack(spacerts.world.Difficulty.NORMAL, 18);
        Faction r = w.factions.get(1);
        run(w, 4);
        assertTrue(warned(w), "warning shown");
        int moving = 0;
        for (Unit u : w.units) if (!u.dead && u.owner == r && u.cls.isWarship() && u.order == spacerts.units.Order.ATTACK_MOVE) moving++;
        assertEquals(0, moving, "fleet waits after the warning");
        run(w, 30);
        for (Unit u : w.units) if (!u.dead && u.owner == r && u.cls.isWarship() && u.order == spacerts.units.Order.ATTACK_MOVE) moving++;
        assertTrue(moving > 0, "fleet moves out after the delay");
    }

    /** Player colony with pirate raiders next to it and idle Lancers 1500 away. */
    private static Unit[] raidSetup(World w, java.util.List<Unit> lancers) {
        Faction p = w.player;
        Body col = colonizeNearest(w, p);
        for (int i = 0; i < 4; i++) {
            Unit l = w.spawn(ShipClass.LANCER, p, col.x + 1500 + i * 30, col.y);
            lancers.add(l);
        }
        Unit[] raiders = new Unit[3];
        for (int i = 0; i < raiders.length; i++) raiders[i] = w.spawn(ShipClass.RAIDER, w.pirates, col.x - col.radius - 200, col.y + i * 30);
        w.flushPending();
        w.updateVision();
        return raiders;
    }

    @Test
    void idleShipsAutoDefendColoniesAndReturnHome() {
        World w = newWorld(23, 1);
        java.util.List<Unit> lancers = new java.util.ArrayList<>();
        Unit[] raiders = raidSetup(w, lancers);
        float hx = lancers.get(0).x;
        run(w, 3);
        boolean dispatched = false;
        for (Unit l : lancers) if (l.autoTask) dispatched = true;
        assertTrue(dispatched, "idle lancers sent to defend");
        run(w, 60);
        for (Unit r : raiders) assertTrue(r.dead, "raiders destroyed");
        run(w, 30);
        int home = 0;
        for (Unit l : lancers) if (!l.dead && !l.autoTask && Math.abs(l.x - hx) < 300) home++;
        assertTrue(home > 0, "defenders went back to their post");
    }

    @Test
    void autoDefenseOffLeavesUnitsAlone() {
        World w = newWorld(23, 1);
        w.player.autoDefense = false;
        java.util.List<Unit> lancers = new java.util.ArrayList<>();
        raidSetup(w, lancers);
        run(w, 5);
        for (Unit l : lancers) {
            assertFalse(l.autoTask);
            assertEquals(spacerts.units.Order.IDLE, l.order);
        }
    }

    @Test
    void harvestersFleeRaidsAndResume() {
        World w = newWorld(24, 1);
        Faction p = w.player;
        Unit h = null;
        run(w, 20); // let them get out to their resources
        for (Unit u : harvesters(w, p))
            if (u.order == spacerts.units.Order.HARVEST && u.dist(p.mothership) > 500) { h = u; break; }
        assertNotNull(h);
        Body job = h.body;
        Unit raider = w.spawn(ShipClass.RAIDER, w.pirates, h.x + 150, h.y);
        w.flushPending();
        run(w, 1.5f);
        assertTrue(h.fleeTimer > 0, "harvester flees");
        raider.kill(true);
        run(w, 12);
        assertEquals(spacerts.units.Order.HARVEST, h.order);
        assertTrue(h.fleeTimer <= 0, "back to work");
        assertEquals(job, h.body, "same resource as before");
    }

    // ------------------------------------------------------------------ solar collectors & asteroid miners

    private static Body nearestBody(World w, Unit from, Body.Kind kind) {
        Body best = null;
        for (Body b : w.bodies)
            if (!b.dead && b.kind == kind && (best == null || from.dist(b.x, b.y) < from.dist(best.x, best.y))) best = b;
        return best;
    }

    private static Unit waitFor(World w, Faction p, ShipClass c, int count) {
        for (int i = 0; i < 150 * 30; i++) {
            w.update(1f / 30f);
            int n = 0;
            Unit last = null;
            for (Unit u : w.units) if (!u.dead && u.owner == p && u.cls == c) { n++; last = u; }
            if (n >= count) return last;
        }
        throw new AssertionError(c + " never finished");
    }

    @Test
    void solarCollectorsProduceEnergyWithoutHauling() {
        World w = newWorld(31, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Body star = nearestBody(w, p.mothership, Body.Kind.STAR);
        java.util.List<Unit> hs = harvesters(w, p);
        assertNotNull(w.construction.order(hs.get(0), ShipClass.SOLAR, nearestBody(w, p.mothership, Body.Kind.ASTEROID), 0, 0), "not on asteroids");
        assertNull(w.construction.order(hs.get(0), ShipClass.SOLAR, star, 0, 0));
        assertNull(w.construction.order(hs.get(1), ShipClass.SOLAR, star, 0, 0));
        assertNotNull(w.construction.order(hs.get(2), ShipClass.SOLAR, star, 0, 0), "max 2 per star");
        Unit solar = waitFor(w, p, ShipClass.SOLAR, 2);
        assertEquals(star, solar.home);
        assertTrue(solar.dist(star.x, star.y) < star.radius + 60, "built beside the star");
        for (Unit h : harvesters(w, p)) h.kill(true);
        run(w, 0.1f);
        float e0 = p.energy;
        run(w, 30);
        assertEquals(e0 + 2 * 6 * 30, p.energy, 10, "2 collectors x 6 energy/s");
    }

    @Test
    void asteroidMinerMinesTheFieldByItself() {
        World w = newWorld(32, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Body rock = nearestBody(w, p.mothership, Body.Kind.ASTEROID);
        java.util.List<Unit> hs = harvesters(w, p);
        assertNotNull(w.construction.order(hs.get(0), ShipClass.MINER, nearestBody(w, p.mothership, Body.Kind.STAR), 0, 0), "not on stars");
        assertNull(w.construction.order(hs.get(0), ShipClass.MINER, rock, 0, 0));
        assertNotNull(w.construction.order(hs.get(1), ShipClass.MINER, rock, 0, 0), "one miner per field");
        Unit miner = waitFor(w, p, ShipClass.MINER, 1);
        for (Unit h : harvesters(w, p)) h.kill(true);
        run(w, 0.1f);
        float m0 = p.metal, field0 = 0;
        for (Body b : w.bodies) if (!b.dead && b.kind == Body.Kind.ASTEROID && miner.dist(b.x, b.y) < 600) field0 += b.metal;
        run(w, 40);
        float field1 = 0;
        for (Body b : w.bodies) if (!b.dead && b.kind == Body.Kind.ASTEROID && miner.dist(b.x, b.y) < 600) field1 += b.metal;
        float gained = p.metal - m0;
        System.out.printf("miner: +%.0f metal in 40s, field %.0f -> %.0f%n", gained, field0, field1);
        assertEquals(5 * 40, gained, 15, "5 metal/s");
        assertEquals(gained, field0 - field1, 15, "taken out of the asteroids");
    }

    @Test
    void factoryAndHubMoveAsSlowlyAsTheMothership() {
        World w = newWorld(33, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Unit fac = buildFactory(w, p);
        Unit hub = w.spawn(ShipClass.HUB, p, p.mothership.x - 400, p.mothership.y - 400);
        w.flushPending();
        assertEquals(p.mothership.speed(), fac.speed(), 0.01);
        assertEquals(p.mothership.speed(), hub.speed(), 0.01);
        assertFalse(fac.cls.isWarship(), "not part of the army (Ctrl+A, auto-defense)");
        assertTrue(fac.cls.isBuilding());
        float x0 = fac.x;
        fac.command(spacerts.units.Order.MOVE, fac.x + 2000, fac.y);
        assertNull(w.production.enqueue(fac, ShipClass.WISP), "still builds while moving");
        run(w, 10);
        float moved = fac.x - x0;
        assertTrue(moved > 200 && moved < 30 * 10 + 20, "crawls: moved " + moved);
        int wisps = 0;
        for (Unit u : w.units) if (!u.dead && u.owner == p && u.cls == ShipClass.WISP) wisps++;
        assertTrue(wisps >= 2, "wisp delivered on the move");
    }

    // ------------------------------------------------------------------ sniper balance

    /** An enemy sniper far outside a lone player Lancer's vision, in an empty corner of the map. */
    private static Unit[] sniperDuel(World w, ShipClass sniperCls) {
        float x = 200, y = 200;
        for (Body b : w.bodies) b.dead = Math.hypot(b.x - 1200, b.y - 200) < 1500 && b.kind == Body.Kind.ASTEROID || b.dead;
        Unit lancer = w.spawn(ShipClass.LANCER, w.player, x + 850, y);
        Unit sniper = w.spawn(sniperCls, w.factions.get(1), x, y);
        w.flushPending();
        w.updateVision();
        return new Unit[]{lancer, sniper};
    }

    @Test
    void snipersAreRevealedWhenTheyFire() {
        World w = newWorld(41, 1);
        Unit[] d = sniperDuel(w, ShipClass.LONGBOW);
        Unit lancer = d[0], sniper = d[1];
        assertFalse(w.canSee(w.player, sniper), "out of sight before it fires");
        lancer.hp = 1000; // survive the shot
        w.combat.fire(sniper, lancer);
        w.updateVision();
        assertTrue(w.canSee(w.player, sniper), "revealed right after firing");
        boolean marker = false;
        for (spacerts.world.Ping p : w.pings) if (p.sniper && p.owner == w.player) marker = true;
        assertTrue(marker, "red sniper marker");
        sniper.cooldown = 99; // don't fire again
        sniper.order = spacerts.units.Order.IDLE;
        for (int i = 0; i < 5 * 30; i++) { sniper.cooldown = 99; w.update(1f / 30f); }
        assertFalse(w.canSee(w.player, sniper), "hidden again after 4 s");
    }

    @Test
    void cloakedPhantomIsDetectedWhenItFires() {
        World w = newWorld(42, 1);
        w.factions.get(1).techs.add(Tech.PHANTOM_TECH);
        Unit[] d = sniperDuel(w, ShipClass.PHANTOM);
        Unit lancer = d[0], ph = d[1];
        lancer.hp = 1000;
        w.time = 100;
        w.combat.fire(ph, lancer);
        w.updateVision();
        w.time += 3.5f; // past the 3 s decloak window, still inside the 4 s reveal
        assertTrue(ph.cloaked(w.time));
        assertTrue(w.canSee(w.player, ph), "the reveal detects the cloaked Phantom");
    }

    @Test
    void lastStandSavesInfantryOncePer30Seconds() {
        World w = newWorld(43, 1);
        Faction p = w.player;
        Unit without = w.spawn(ShipClass.LANCER, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(without, 500, true, null);
        assertTrue(without.dead, "no research: dies");

        p.techs.add(Tech.LAST_STAND);
        Unit l = w.spawn(ShipClass.LANCER, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(l, 500, true, null);
        assertFalse(l.dead, "survives the killing blow");
        assertEquals(1f, l.hp, 0.001f);
        w.time += 10;
        w.combat.applyDamage(l, 500, true, null);
        assertTrue(l.dead, "only once per 30 s");

        Unit l2 = w.spawn(ShipClass.LANCER, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(l2, 500, true, null);
        l2.hp = l2.maxHp();
        w.time += 31;
        w.combat.applyDamage(l2, 500, true, null);
        assertFalse(l2.dead, "recharged after 30 s");
        Unit bulwark = w.spawn(ShipClass.BULWARK, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(bulwark, 99999, true, null);
        assertTrue(bulwark.dead, "infantry only");
        assertEquals(5.5f, ShipClass.LONGBOW.s.cooldown, 0.001f);
        assertEquals(6f, ShipClass.PHANTOM.s.cooldown, 0.001f);
    }

    @Test
    void damagedShipsRetreatToMenders() {
        World w = newWorld(25, 1);
        Faction p = w.player;
        Unit m = w.spawn(ShipClass.MENDER, p, p.mothership.x + 300, p.mothership.y + 300);
        Unit b = w.spawn(ShipClass.BULWARK, p, m.x + 1500, m.y);
        w.flushPending();
        b.hp = b.maxHp() * 0.2f;
        run(w, 25);
        assertTrue(b.retreating, "retreating");
        assertTrue(b.dist(m) < m.s.healRange, "inside the repair zone");
    }
}
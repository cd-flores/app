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
        double e0 = p.energy, m0 = p.metal;
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
        assertEquals(World.BASE_HOUSING + 65, p.popCap, "fortified world gives +65 housing");
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
        assertNotNull(w.production.blocker(fac, ShipClass.LEVIATHAN), "needs a tier III factory");
        fac.tier = 3;
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
        double before = p.energy;
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
        assertEquals(shield0 + 2 * Body.SHIELD_PER_POINT * Unit.DURABILITY * Unit.COLONY_SHIELD_MULT, b.colony.maxShield(), 0.01, "+400 (x durability) shield per shield point");
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
        assertEquals((ShipClass.COLONY.s.shield + 5 * Body.SHIELD_PER_POINT) * Unit.DURABILITY * Unit.COLONY_SHIELD_MULT, b.colony.maxShield(), 0.01);
        // the shield recharges faster after damage
        b.colony.shield = 0;
        b.colony.lastHit = w.time - 10;
        run(w, 1);
        assertTrue(b.colony.shield > (ShipClass.COLONY.s.shieldRegen + 5 * Body.REGEN_PER_POINT) * Unit.DURABILITY * Unit.SHIELD_REGEN_MULT * Unit.COLONY_SHIELD_MULT * 0.9f, "faster recharge");
    }

    @Test
    void harvesterPlacesRepairStation() {
        World w = newWorld(9, 1);
        Faction p = w.player;
        p.energy = 2000;
        p.metal = 2000;
        Unit h = harvesters(w, p).get(0);
        assertTrue(ShipClass.MENDER.needsSite(), "placed where you click");
        float[] s = site(w, p, p.mothership, ShipClass.MENDER.s.radius);
        assertNull(w.construction.order(h, ShipClass.MENDER, null, s[0], s[1]));
        Unit m = waitFor(w, p, ShipClass.MENDER, 1);
        assertEquals(s[0], m.x, 1f, "built at the clicked spot");
        assertEquals(s[1], m.y, 1f);
        assertTrue(m.cls.isStation() && m.cls.isBuilding(), "a station, not a ship");
        assertFalse(m.cls.isWarship());
        assertEquals(0, ShipClass.MENDER.s.pop, "no housing");
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
        for (Unit h : harvesters(w, p)) h.kill(true); // the colony's free harvester
        run(w, 0.1f);
        double e0 = p.energy, m0 = p.metal;
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
            assertTrue(w.systemAt(u.x, u.y).inside(u.x, u.y, 0), "units stay on the map");
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
        double e0 = p.energy;
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
        double m0 = p.metal, field0 = 0;
        for (Body b : w.bodies) if (!b.dead && b.kind == Body.Kind.ASTEROID && miner.dist(b.x, b.y) < 600) field0 += b.metal;
        run(w, 40);
        float field1 = 0;
        for (Body b : w.bodies) if (!b.dead && b.kind == Body.Kind.ASTEROID && miner.dist(b.x, b.y) < 600) field1 += b.metal;
        double gained = p.metal - m0;
        System.out.printf("miner: +%.0f metal in 40s, field %.0f -> %.0f%n", gained, field0, field1);
        assertEquals(5 * 40, gained, 15, "5 metal/s");
        assertEquals(gained, field0 - field1, 15, "taken out of the asteroids");
    }

    @Test
    void stationsCrawlAtHalfTheMothershipSpeed() {
        World w = newWorld(33, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Unit fac = buildFactory(w, p);
        Unit hub = w.spawn(ShipClass.HUB, p, p.mothership.x - 400, p.mothership.y - 400);
        w.flushPending();
        assertEquals(p.mothership.speed() / 2f, fac.speed(), 0.01);
        assertEquals(p.mothership.speed() / 2f, hub.speed(), 0.01);
        assertFalse(fac.cls.isWarship(), "not part of the army (Ctrl+A, auto-defense)");
        assertTrue(fac.cls.isBuilding());
        float x0 = fac.x;
        fac.command(spacerts.units.Order.MOVE, fac.x + 2000, fac.y);
        assertNull(w.production.enqueue(fac, ShipClass.WISP), "still builds while moving");
        run(w, 10);
        float moved = fac.x - x0;
        assertTrue(moved > 100 && moved < 15 * 10 + 20, "crawls at half speed: moved " + moved);
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
        float kill = ShipClass.LANCER.s.hp * Unit.DURABILITY + 100; // more than a lancer's hull
        Unit without = w.spawn(ShipClass.LANCER, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(without, kill, true, null);
        assertTrue(without.dead, "no research: dies");

        p.techs.add(Tech.LAST_STAND);
        Unit l = w.spawn(ShipClass.LANCER, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(l, kill, true, null);
        assertFalse(l.dead, "survives the killing blow");
        assertEquals(1f, l.hp, 0.001f);
        w.time += 10;
        w.combat.applyDamage(l, kill, true, null);
        assertTrue(l.dead, "only once per 30 s");

        Unit l2 = w.spawn(ShipClass.LANCER, p, 300, 300);
        w.flushPending();
        w.combat.applyDamage(l2, kill, true, null);
        l2.hp = l2.maxHp();
        w.time += 31;
        w.combat.applyDamage(l2, kill, true, null);
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

    // ------------------------------------------------------------------ long skirmishes, mining, retreat, boosts

    @Test
    void durabilityScalesHpAndShields() {
        World w = newWorld(51, 1);
        Unit b = w.spawn(ShipClass.BULWARK, w.player, 500, 500);
        w.flushPending();
        assertEquals(ShipClass.BULWARK.s.hp * Unit.DURABILITY, b.maxHp(), 0.01);
        assertEquals(ShipClass.BULWARK.s.shield * Unit.DURABILITY, b.maxShield(), 0.01);
        assertEquals(b.maxHp(), b.hp, 0.01, "spawns at full (scaled) hull");
        assertEquals(30, PlanetType.HABITABLE.housing);
        assertEquals(65, PlanetType.DEFENDED.housing);
        assertEquals(1.0f, spacerts.systems.EconomySystem.colonyIncome(firstPlanet(w, PlanetType.HABITABLE)), 0.001f, "habitable income unchanged");
        assertEquals(1.5f, spacerts.systems.EconomySystem.colonyIncome(firstPlanet(w, PlanetType.DEFENDED)), 0.001f);
    }

    private static Body firstPlanet(World w, PlanetType t) {
        for (Body b : w.bodies) if (b.type == t) return b;
        throw new AssertionError("no " + t);
    }

    @Test
    void bargeCarriesMoreAndCannotBuild() {
        World w = newWorld(52, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Unit fac = buildFactory(w, p);
        assertNull(w.production.enqueue(fac, ShipClass.BARGE), "factory builds barges");
        Unit barge = waitFor(w, p, ShipClass.BARGE, 1);
        assertEquals(150, w.economy.capacity(barge), 0.01);
        float[] s = site(w, p, p.mothership, ShipClass.FACTORY.s.radius);
        assertNotNull(w.construction.order(barge, ShipClass.FACTORY, null, s[0], s[1]), "barges can't build");
        run(w, 5);
        assertEquals(spacerts.units.Order.HARVEST, barge.order, "goes mining on its own");
    }

    @Test
    void destroyedShipLeavesSalvageableWreck() {
        World w = newWorld(53, 1);
        Faction p = w.player;
        Faction rival = w.factions.get(1);
        Unit enemy = w.spawn(ShipClass.BULWARK, rival, p.mothership.x + 400, p.mothership.y + 200);
        w.flushPending();
        enemy.kill(false);
        run(w, 0.1f);
        Body wreck = null;
        for (Body b : w.bodies) if (b.isWreck() && !b.dead) wreck = b;
        assertNotNull(wreck, "wreck left behind");
        assertEquals(ShipClass.BULWARK.s.metal * 0.5f, wreck.metal, 0.5f, "half its metal cost");
        assertTrue(wreck.harvestable());

        Unit h = harvesters(w, p).get(0);
        w.economy.assign(h, wreck);
        double m0 = p.metal, salvage = wreck.metal;
        run(w, 40);
        assertTrue(wreck.dead || wreck.metal < salvage, "salvaged");
        assertTrue(p.metal > m0, "salvage delivered");

        Body old = new Body(Body.Kind.WRECK, 900, 900, 10);
        old.metal = old.maxMetal = 30;
        old.life = 0.5f;
        w.bodies.add(old);
        run(w, 1);
        assertTrue(old.dead, "wrecks drift away");
    }

    @Test
    void damagedShipRetreatsToBaseAndRepairs() {
        World w = newWorld(54, 1);
        Faction p = w.player;
        Unit ms = p.mothership;
        Unit b = w.spawn(ShipClass.BULWARK, p, ms.x + 1500, ms.y);
        Unit stubborn = w.spawn(ShipClass.BULWARK, p, ms.x + 1500, ms.y + 200);
        w.flushPending();
        stubborn.retreatOff = true;
        b.command(spacerts.units.Order.ATTACK_MOVE, ms.x + 3000, ms.y); // even mid-order
        b.hp = b.maxHp() * 0.2f;
        stubborn.hp = stubborn.maxHp() * 0.2f;
        run(w, 30);
        assertTrue(b.retreating, "pulls back below 25%");
        assertTrue(b.dist(ms) - ms.radius < spacerts.systems.UnitBehavior.REPAIR_RANGE + 40, "back at the mothership");
        assertTrue(b.hp > b.maxHp() * 0.2f + 1, "repairing at base");
        assertFalse(stubborn.retreating, "auto-retreat off: stays put");
        run(w, 50);
        assertFalse(b.retreating, "repaired and back in the fight");
        assertTrue(b.hp >= b.maxHp() * Unit.RETREAT_DONE - 1);
    }

    @Test
    void attackAndPlanetBoosts() {
        World w = newWorld(55, 1);
        Faction p = w.player;
        Unit u = w.spawn(ShipClass.BULWARK, p, 600, 600);
        w.flushPending();
        // an open-space point far from every planet
        float gx = -1, gy = -1;
        for (int i = 0; i < 4000 && gx < 0; i++) {
            float x = 500 + (i * 977) % 11000, y = 500 + (i * 613) % 11000;
            boolean clear = true;
            for (Body b : w.bodies) if (b.isPlanet() && Math.hypot(b.x - x, b.y - y) < b.radius + 800) clear = false;
            if (clear) { gx = x; gy = y; }
        }
        assertTrue(gx >= 0);
        u.hasGoal = true;
        u.gx = gx;
        u.gy = gy;
        u.order = spacerts.units.Order.MOVE;
        assertEquals(1f, w.movement.boost(u), 0.001f, "plain move in open space");
        u.order = spacerts.units.Order.ATTACK_MOVE;
        assertEquals(spacerts.systems.MovementSystem.BOOST_ATTACK, w.movement.boost(u), 0.001f, "charging an attack");
        Unit enemy = w.spawn(ShipClass.LANCER, w.factions.get(1), u.x + 100, u.y);
        w.flushPending();
        u.order = spacerts.units.Order.ATTACK;
        u.target = enemy;
        assertEquals(1f, w.movement.boost(u), 0.001f, "no boost once in weapon range");
        Body planet = firstPlanet(w, PlanetType.VOLCANIC);
        u.order = spacerts.units.Order.MOVE;
        u.target = null;
        u.gx = planet.x;
        u.gy = planet.y;
        assertEquals(spacerts.systems.MovementSystem.BOOST_PLANET, w.movement.boost(u), 0.001f, "toward any planet");
        Body col = colonizeNearest(w, p);
        u.gx = col.x;
        u.gy = col.y;
        assertEquals(spacerts.systems.MovementSystem.BOOST_HOME, w.movement.boost(u), 0.001f, "toward own colony");
        Unit pirate = w.spawn(ShipClass.RAIDER, w.pirates, 700, 700);
        w.flushPending();
        pirate.hasGoal = true;
        pirate.gx = planet.x;
        pirate.gy = planet.y;
        assertEquals(1f, w.movement.boost(pirate), 0.001f, "pirates don't get planet boosts");
    }

    @Test
    void colonyUpgradeSendsNearestFreeHarvester() {
        World w = newWorld(56, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Body col = colonizeNearest(w, p);
        Unit h = w.construction.nearestFreeBuilder(p, col);
        assertNotNull(h);
        assertEquals(ShipClass.HARVESTER, h.cls);
        assertNull(w.construction.order(h, ShipClass.DEF_TURRET, col, col.x, col.y));
        assertNotSame(h, w.construction.nearestFreeBuilder(p, col), "a busy builder isn't picked twice");
        for (int i = 0; i < 120 * 30 && col.defensePoints() == 0; i++) w.update(1f / 30f);
        assertEquals(1, col.turretPoints, "turret upgrade built");
        run(w, 1);
        assertEquals(spacerts.units.Order.HARVEST, h.order, "back to harvesting");
        for (ShipClass c : ShipClass.HARVESTER.buildList()) assertFalse(c.isPlanetUpgrade(), "upgrades moved off the harvester list");
    }

    @Test
    void newColonySpawnsAHarvester() {
        World w = newWorld(57, 1);
        Faction p = w.player;
        int before = harvesters(w, p).size();
        Body col = colonizeNearest(w, p);
        java.util.List<Unit> after = harvesters(w, p);
        assertEquals(before + 1, after.size(), "+1 harvester");
        Unit fresh = after.get(after.size() - 1);
        assertTrue(fresh.dist(col.x, col.y) < col.radius + 60, "spawned at the colony");
        assertEquals(spacerts.units.Order.HARVEST, fresh.order, "starts working at once");
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        int loaded = 0;
        for (Unit u : l.units) if (u.owner == l.player && u.s.harvester) loaded++;
        assertEquals(after.size(), loaded, "loading doesn't add harvesters");
    }

    @Test
    void wrecksAndRetreatStateSurviveSaveLoad() {
        World w = newWorld(58, 1);
        Faction p = w.player;
        Unit enemy = w.spawn(ShipClass.LONGBOW, w.factions.get(1), p.mothership.x + 500, p.mothership.y);
        Unit b = w.spawn(ShipClass.BULWARK, p, p.mothership.x + 900, p.mothership.y);
        w.flushPending();
        enemy.kill(false);
        b.retreating = true;
        b.retreatOff = true;
        b.homeX = 4321;
        run(w, 0.1f);
        Body wreck = null;
        for (Body x : w.bodies) if (x.isWreck() && !x.dead) wreck = x;
        assertNotNull(wreck);
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        Body lw = null;
        for (Body x : l.bodies) if (x.isWreck()) lw = x;
        assertNotNull(lw, "wreck saved");
        assertEquals(wreck.metal, lw.metal, 0.01);
        assertEquals(wreck.life, lw.life, 0.01);
        Unit lb = null;
        for (Unit u : l.units) if (u.id == b.id) lb = u;
        assertNotNull(lb);
        assertTrue(lb.retreatOff);
        assertEquals(4321, lb.homeX, 0.01);
    }

    // ------------------------------------------------------------------ pirates-only Hive galaxy

    private static World hive(long seed) {
        return newWorld(seed, 0);
    }

    @Test
    void hiveHas91HexesWithGatesBothWays() {
        World w = hive(61);
        spacerts.world.Galaxy g = w.galaxy;
        assertTrue(g.hive && w.piratesOnly());
        assertEquals(91, g.systems.size(), "centre + 5 rings");
        for (Faction f : w.factions) assertNotEquals(Faction.Kind.RIVAL, f.kind, "no rival empires");
        int gates = 0;
        for (spacerts.world.StarSystem s : g.systems) {
            for (int k = 0; k < 6; k++) {
                Body gate = s.gates[k];
                assertEquals(s.neighbours[k] != null, gate != null, "a gate on every shared edge");
                if (gate == null) continue;
                gates++;
                assertSame(gate, gate.link.link, "gates are paired");
                assertSame(s.neighbours[k], w.systemAt(gate.link.x, gate.link.y), "leads to the neighbour");
                assertSame(s, w.systemAt(gate.x, gate.y));
            }
            assertTrue(g.jumps(g.home(), s) <= 10, "every hex reachable from home");
            assertEquals(g.jumps(g.home(), s), s.danger, "danger = jumps from home");
        }
        assertEquals(2 * 3 * 5 * (3 * 5 + 1), gates, "a 5-ring hive has 240 shared edges, 2 gates each");
        for (Body b : w.bodies) assertTrue(b.system.contains(b.x, b.y), "bodies stay inside their hex");
        assertSame(g.home(), w.systemAt(w.player.mothership.x, w.player.mothership.y), "player starts at the bottom");
        assertEquals(10, g.citadel().danger, "the Citadel is at the very top");
        assertTrue(g.home().cy < g.citadel().cy, "home is below the Citadel");
    }

    @Test
    void archetypesDiffer() {
        World w = hive(62);
        float[] stars = new float[spacerts.world.SystemArchetype.values().length];
        float[] rocks = new float[stars.length], gardens = new float[stars.length], count = new float[stars.length];
        for (spacerts.world.StarSystem s : w.galaxy.systems) {
            int a = s.archetype.ordinal();
            count[a]++;
            for (Body b : s.bodies) {
                if (b.kind == Body.Kind.STAR) stars[a]++;
                if (b.kind == Body.Kind.ASTEROID) rocks[a]++;
                if (b.type == PlanetType.HABITABLE) gardens[a]++;
            }
        }
        int nur = spacerts.world.SystemArchetype.NURSERY.ordinal(), belt = spacerts.world.SystemArchetype.BELT.ordinal(),
                gar = spacerts.world.SystemArchetype.GARDEN.ordinal();
        assertTrue(count[nur] > 0 && count[belt] > 0 && count[gar] > 0, "variety exists");
        assertTrue(stars[nur] / count[nur] > stars[belt] / count[belt] + 1.5f, "nurseries are full of stars");
        assertTrue(rocks[belt] / count[belt] > rocks[gar] / count[gar] * 3, "belts are full of asteroids");
        assertTrue(gardens[gar] / count[gar] >= 3.5f, "gardens are full of habitable worlds");
    }

    @Test
    void shipRoutesThroughGatesToAnotherHex() {
        World w = hive(63);
        spacerts.world.StarSystem home = w.galaxy.home();
        spacerts.world.StarSystem far = null;
        for (spacerts.world.StarSystem s : w.galaxy.systems) if (w.galaxy.jumps(home, s) == 2) { far = s; break; }
        assertNotNull(far);
        Unit l = w.spawn(ShipClass.LEVIATHAN, w.player, home.cx + 300, home.cy + 300);
        w.flushPending();
        l.hp = 1e7f; // don't let natives on the way spoil the trip
        l.command(spacerts.units.Order.MOVE, far.cx, far.cy);
        float t = 0;
        while (t < 3000 && w.systemAt(l.x, l.y) != far) { w.update(1f / 30f); t += 1f / 30f; }
        System.out.printf("2-jump trip took %.0fs%n", t);
        assertSame(far, w.systemAt(l.x, l.y), "arrived through the gates");
    }

    @Test
    void piratesOnlyHasBasesWithGarrisons() {
        World w = hive(64);
        java.util.List<Unit> bases = spacerts.world.PirateBases.livingBases(w);
        int havens = 0;
        for (spacerts.world.StarSystem s : w.galaxy.systems) if (s.archetype == spacerts.world.SystemArchetype.HAVEN) havens++;
        assertTrue(havens >= 4, "several Pirate Havens");
        assertEquals(havens + 1, bases.size(), "one stronghold per Pirate Haven, plus the Citadel");
        for (Unit b : bases) {
            spacerts.world.StarSystem s = w.systemAt(b.x, b.y);
            if (b.tier >= 3) {
                assertEquals(spacerts.world.SystemArchetype.CITADEL, s.archetype);
                assertEquals(spacerts.world.PirateBases.CITADEL_GARRISON, spacerts.world.PirateBases.garrisonSize(w, b), "big garrison");
                assertTrue(b.maxHp() >= ShipClass.PIRATE_BASE.s.hp * Unit.DURABILITY * 3 - 1, "3x hull");
                continue;
            }
            assertEquals(spacerts.world.SystemArchetype.HAVEN, s.archetype);
            assertTrue(s.danger >= spacerts.world.HiveGenerator.HAVEN_DANGER_MIN, "far from home");
            assertEquals(spacerts.world.PirateBases.START_GARRISON, spacerts.world.PirateBases.garrisonSize(w, b), "ships on station");
        }
        assertTrue(w.threats.nextWave >= spacerts.world.PirateBases.GRACE, "grace period before the first raid");
        run(w, 60);
        for (Unit b : bases) {
            assertFalse(b.dead);
            for (Unit u : w.units)
                if (!u.dead && u.station == b) assertTrue(u.dist(b) < spacerts.world.PirateBases.LEASH + 200, "garrison stays home");
        }
    }

    @Test
    void raidsLaunchFromStrongholds() {
        World w = hive(65);
        java.util.List<Unit> bases = spacerts.world.PirateBases.livingBases(w);
        java.util.Set<Integer> before = new java.util.HashSet<>();
        for (Unit u : w.units) before.add(u.id);
        w.threats.nextWave = w.time;
        run(w, 0.1f);
        int raiders = 0, nearBase = 0;
        for (Unit u : w.units) {
            if (u.dead || u.owner != w.pirates || before.contains(u.id) || u.station != null) continue;
            raiders++;
            for (Unit b : bases) if (u.dist(b) < 600) { nearBase++; break; }
        }
        assertTrue(raiders > 0, "raid spawned");
        assertEquals(raiders, nearBase, "every raider launched from a stronghold");
        boolean msg = false;
        for (spacerts.world.Message m : w.messages) if (m.text.contains("launched from")) msg = true;
        assertTrue(msg, "the alert names the source");
    }

    @Test
    void destroyedStrongholdLeavesWreckAndRespawns() {
        World w = hive(66);
        Unit base = null;
        for (Unit b : spacerts.world.PirateBases.livingBases(w)) if (b.tier < 3) { base = b; break; }
        int count0 = spacerts.world.PirateBases.livingBases(w).size();
        java.util.List<Unit> garrison = new java.util.ArrayList<>();
        for (Unit u : w.units) if (u.station == base) garrison.add(u);
        base.kill(false);
        run(w, 0.1f);
        for (Unit g : garrison) assertNull(g.station, "garrison turns raider");
        Body wreck = null;
        for (Body b : w.bodies) if (b.isWreck() && !b.dead && Math.hypot(b.x - base.x, b.y - base.y) < 100) wreck = b;
        assertNotNull(wreck);
        assertEquals(1500, wreck.metal, 1);
        assertEquals(2, wreck.lootNeutron, "strongholds always leave Neutronium");
        assertEquals(count0 - 1, spacerts.world.PirateBases.livingBases(w).size());
        assertTrue(w.pirateRespawn >= spacerts.world.PirateBases.RESPAWN_DELAY - 1, "respawn clock restarted");
        w.pirateRespawn = 0.5f;
        run(w, 1);
        java.util.List<Unit> after = spacerts.world.PirateBases.livingBases(w);
        assertEquals(count0, after.size(), "a new stronghold rises");
        Unit fresh = after.get(after.size() - 1);
        assertTrue(w.galaxy.jumps(w.systemAt(fresh.x, fresh.y), w.galaxy.home()) >= 3, "far from the player");
    }

    @Test
    void hiveLongGameStableAndRoundTrips() {
        World w = hive(2027);
        long t0 = System.currentTimeMillis();
        run(w, 30 * 60);
        long ms = System.currentTimeMillis() - t0;
        int pirates = 0;
        for (Unit u : w.units) if (!u.dead && u.owner == w.pirates) pirates++;
        System.out.printf("Hive: 30 min simulated in %d ms; units=%d pirates=%d bases=%d waves=%d bodies=%d%n", ms, w.units.size(), pirates,
                spacerts.world.PirateBases.livingBases(w).size(), w.threats.wave, w.bodies.size());
        assertTrue(w.threats.wave >= 5, "raids keep coming");
        for (Unit u : w.units) {
            assertFalse(Float.isNaN(u.x) || Float.isNaN(u.y) || Float.isNaN(u.hp), "no NaN state");
            assertTrue(w.systemAt(u.x, u.y).inside(u.x, u.y, 0), "units stay inside a hex");
        }
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        assertTrue(l.galaxy.hive);
        assertEquals(w.units.size(), l.units.size());
        int ga = 0, gb = 0, sa = 0, sb = 0;
        for (Body b : w.bodies) if (!b.dead && b.isGate() && b.link != null) ga++;
        for (Body b : l.bodies) if (b.isGate() && b.link != null && b.link.link == b) gb++;
        for (Unit u : w.units) if (!u.dead && u.station != null && !u.station.dead) sa++;
        for (Unit u : l.units) if (u.station != null) sb++;
        assertEquals(ga, gb, "gate links restored");
        assertEquals(sa, sb, "garrisons restored");
        for (int i = 0; i < w.galaxy.systems.size(); i++) {
            assertEquals(w.galaxy.systems.get(i).name, l.galaxy.systems.get(i).name);
            assertEquals(w.galaxy.systems.get(i).archetype, l.galaxy.systems.get(i).archetype);
        }
        run(l, 30);
    }

    // ------------------------------------------------------------------ tiers, rare finds, conquest, the Ascension Gate

    /** Runs a producer's whole queue (upgrades included). */
    private static void finishQueue(World w, Unit p) {
        for (int i = 0; i < 400 * 30 && !p.queue.isEmpty(); i++) w.update(1f / 30f);
    }

    @Test
    void salvageIsHalfTheMetalCost() {
        World w = newWorld(71, 1);
        Unit b = w.spawn(ShipClass.SOVEREIGN, w.factions.get(1), 500, 500);
        Unit r = w.spawn(ShipClass.RAIDER, w.pirates, 900, 900);
        w.flushPending();
        assertEquals(ShipClass.SOVEREIGN.s.metal * 0.5f, World.salvageValue(b), 0.01);
        assertEquals(40, World.salvageValue(r), 0.01, "pirate salvage doubled too");
    }

    @Test
    void rareFindsDropAtLowRates() {
        World w = newWorld(72, 1);
        Faction p = w.player;
        p.energy = p.metal = 10000;
        Body star = nearestBody(w, p.mothership, Body.Kind.STAR);
        java.util.List<Unit> hs = harvesters(w, p);
        assertNull(w.construction.order(hs.get(0), ShipClass.SOLAR, star, 0, 0));
        assertNull(w.construction.order(hs.get(1), ShipClass.SOLAR, star, 0, 0));
        waitFor(w, p, ShipClass.SOLAR, 2);
        int star0 = p.rare[spacerts.research.RareItem.STAR_ESSENCE.ordinal()];
        run(w, 30 * 60);
        int found = p.rare[spacerts.research.RareItem.STAR_ESSENCE.ordinal()] - star0;
        System.out.printf("Star Essence found in 30 min: %d%n", found);
        // 2 collectors x 4%/min x 30 min ~ 2.4 expected, plus the odd harvester find
        assertTrue(found >= 1 && found <= 12, "scarce but not absent: " + found);
        assertTrue(p.found[spacerts.research.RareItem.STAR_ESSENCE.ordinal()], "shows in the top bar once found");
    }

    @Test
    void mothershipTiersUnlockBuildings() {
        World w = newWorld(73, 1);
        Faction p = w.player;
        p.energy = p.metal = 20000;
        for (int i = 0; i < spacerts.research.RareItem.COUNT; i++) p.rare[i] = 20;
        Unit ms = p.mothership;
        float hp1 = ms.maxHp();
        assertNotNull(w.construction.baseBlocker(p, ShipClass.PLATFORM), "Defense Platform locked at tier I");
        assertNotNull(w.production.enqueue(ms, ShipClass.MS_TIER_3), "tier III needs tier II first");
        assertNull(w.production.enqueue(ms, ShipClass.MS_TIER_2));
        finishQueue(w, ms);
        assertEquals(2, ms.tier);
        assertEquals(2, p.msTier());
        assertTrue(ms.maxHp() > hp1 * 1.29f, "+30% hull");
        assertEquals(ShipClass.MOTHERSHIP.s.multi + 1, ms.multi(), "an extra gun");
        assertNull(w.construction.baseBlocker(p, ShipClass.PLATFORM), "Defense Platform unlocked");
        assertNotNull(w.construction.baseBlocker(p, ShipClass.ION_CANNON), "Ion Cannon still needs tier III");
        assertNull(w.production.enqueue(ms, ShipClass.MS_TIER_3));
        finishQueue(w, ms);
        assertEquals(3, ms.tier);
        assertNull(w.construction.baseBlocker(p, ShipClass.ION_CANNON));
        assertNotNull(w.production.enqueue(ms, ShipClass.MS_TIER_3), "already tier III");
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        assertEquals(3, l.player.mothership.tier, "tier survives save/load");
    }

    @Test
    void factoryTierBuildsFasterAndUnlocksShips() {
        World w = newWorld(74, 1);
        Faction p = w.player;
        p.energy = p.metal = 20000;
        for (int i = 0; i < spacerts.research.RareItem.COUNT; i++) p.rare[i] = 20;
        Unit fac = buildFactory(w, p);
        assertNotNull(w.production.enqueue(fac, ShipClass.CORSAIR), "Corsair needs a tier II factory");
        assertNull(w.production.enqueue(fac, ShipClass.FACTORY_TIER_2));
        finishQueue(w, fac);
        assertEquals(2, fac.tier);
        assertNull(w.production.enqueue(fac, ShipClass.CORSAIR), "Corsair unlocked");
        assertNotNull(w.production.blocker(fac, ShipClass.SOVEREIGN), "Sovereign needs tier III");
        assertEquals(1.3f, spacerts.systems.ProductionSystem.tierSpeed(fac), 0.001f, "builds 30% faster");
        fac.tier = 3;
        assertEquals(1.6f, spacerts.systems.ProductionSystem.tierSpeed(fac), 0.001f);
        assertNull(w.production.blocker(fac, ShipClass.SOVEREIGN));
    }

    @Test
    void hubTierSpeedsResearch() {
        World w = newWorld(75, 1);
        Faction p = w.player;
        assertEquals(1f, spacerts.systems.ProductionSystem.researchSpeed(p), 0.001f, "no hub");
        Unit hub = w.spawn(ShipClass.HUB, p, p.mothership.x + 500, p.mothership.y);
        w.flushPending();
        p.techs.add(Tech.HUB_TIER_2);
        assertEquals(1.25f, spacerts.systems.ProductionSystem.researchSpeed(p), 0.001f);
        p.techs.add(Tech.HUB_TIER_3);
        assertEquals(1.5f, spacerts.systems.ProductionSystem.researchSpeed(p), 0.001f);
        assertFalse(hub.dead);
    }

    @Test
    void hivePatrolsGrowStrongerUpTheHive() {
        World w = hive(76);
        int[] ships = new int[11], hexes = new int[11];
        for (spacerts.world.StarSystem s : w.galaxy.systems) {
            hexes[s.danger]++;
            ships[s.danger] += spacerts.world.PirateBases.patrolsIn(w, s);
        }
        assertEquals(0, ships[0], "home is clear");
        float low = (ships[1] + ships[2]) / (float) (hexes[1] + hexes[2]);
        float high = (ships[8] + ships[9] + ships[10]) / (float) (hexes[8] + hexes[9] + hexes[10]);
        System.out.printf("patrol ships per hex: danger 1-2 %.1f, danger 8-10 %.1f%n", low, high);
        assertTrue(low > 0 && low <= 8, "a few pirate fleets near home");
        assertTrue(high > low * 3, "much stronger near the top");
        assertEquals(0, spacerts.systems.ConquestSystem.ownedCount(w), "nothing conquered at the start");
    }

    @Test
    void claimBeaconConquersAHex() {
        World w = hive(77);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        spacerts.world.StarSystem home = w.galaxy.home();
        assertEquals(spacerts.world.StarSystem.Control.GREY, home.control);
        Unit h = harvesters(w, p).get(0);
        float[] s = site(w, p, p.mothership, ShipClass.BEACON.s.radius);
        assertNull(w.construction.order(h, ShipClass.BEACON, null, s[0], s[1]));
        waitFor(w, p, ShipClass.BEACON, 1);
        run(w, 2.5f);
        assertTrue(home.owned(), "hex claimed");
        assertEquals(1, spacerts.systems.ConquestSystem.ownedCount(w));
        Unit h2 = harvesters(w, p).get(1);
        float[] s2 = site(w, p, p.mothership, ShipClass.BEACON.s.radius);
        assertNotNull(w.construction.order(h2, ShipClass.BEACON, null, s2[0], s2[1]), "one beacon per hex");
        // a pirate stronghold blocks claiming its hex
        Unit base = spacerts.world.PirateBases.livingBases(w).get(0);
        spacerts.world.StarSystem bs = w.systemAt(base.x, base.y);
        w.player.vision.reveal(base.x + 600, base.y, 400);
        assertNotNull(w.construction.blocker(h2, ShipClass.BEACON, null, base.x + 600, base.y));
        assertEquals(spacerts.world.StarSystem.Control.PIRATE, bs.control);
        // no stronghold rises in or next to owned hexes
        for (int i = 0; i < 20; i++) {
            spacerts.world.StarSystem r = spacerts.world.PirateBases.respawnSystemForTest(w);
            if (r == null) break;
            assertFalse(r.owned());
            for (spacerts.world.StarSystem nb : r.neighbours) assertTrue(nb == null || !nb.owned());
        }
        // hex income
        double e0 = p.energy;
        for (Unit u : harvesters(w, p)) u.kill(true);
        for (Unit u : w.units) if (u.cls == ShipClass.COLONY && u.owner == p) u.kill(true);
        run(w, 0.1f);
        e0 = p.energy;
        run(w, 20);
        assertEquals(e0 + 20 * spacerts.systems.ConquestSystem.HEX_INCOME, p.energy, 2, "+0.5 energy/s per owned hex");
    }

    @Test
    void ascensionGateWinsTheGame() {
        World w = hive(78);
        Faction p = w.player;
        p.energy = p.metal = 50000;
        for (int i = 0; i < spacerts.research.RareItem.COUNT; i++) p.rare[i] = 20;
        spacerts.world.StarSystem top = w.galaxy.citadel();
        Unit h = harvesters(w, p).get(0);
        assertNotNull(w.construction.baseBlocker(p, ShipClass.ASCENSION), "needs Mothership tier III");
        p.mothership.tier = 3;
        float gx = top.cx + 1500, gy = top.cy - 900;
        w.player.vision.reveal(gx, gy, 600);
        assertNotNull(w.construction.order(h, ShipClass.ASCENSION, null, gx, gy), "the Citadel hex must be conquered first");
        // conquer the top: wipe out the pirates there and plant a beacon
        for (Unit u : w.units) if (u.owner == w.pirates && w.systemAt(u.x, u.y) == top) u.kill(true);
        Unit beacon = w.spawn(ShipClass.BEACON, p, top.cx - 1500, top.cy - 900);
        w.flushPending();
        h.x = gx - 200;
        h.y = gy;
        run(w, 2.5f);
        assertTrue(top.owned());
        assertNull(w.construction.order(h, ShipClass.ASCENSION, null, gx, gy));
        h.work = 0.999f; // skip most of the 8-minute build
        for (int i = 0; i < 5 * 30 && !w.ascended; i++) { h.hp = h.maxHp(); w.update(1f / 30f); }
        assertTrue(w.ascended, "the Ascension Gate completes: victory");
        assertFalse(beacon.dead);
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        assertTrue(l.ascended, "victory saved");
    }

    // ------------------------------------------------------------------ logarithmic warship pricing

    @Test
    void warshipCostsGrowLogarithmically() {
        World w = newWorld(81, 1);
        Faction p = w.player;
        for (Unit u : w.units) if (u.owner == p && u.cls == ShipClass.LANCER) u.kill(true); // drop the starting squad
        run(w, 0.1f);
        spacerts.systems.ProductionSystem ps = w.production;
        assertEquals(ShipClass.LANCER.s.energy, ps.energyCost(p, ShipClass.LANCER), "first copy at base price");
        for (int i = 0; i < 27; i++) w.spawn(ShipClass.LANCER, p, 900 + i * 15, 900); // 9 squads
        w.flushPending();
        assertEquals(9, ps.copies(p, ShipClass.LANCER), "counted per squad");
        float mult = 1f + spacerts.systems.ProductionSystem.PRICE_K * (float) Math.log(10);
        assertEquals(mult, ps.priceMult(p, ShipClass.LANCER), 0.001f);
        assertEquals(Math.round(ShipClass.LANCER.s.energy * mult / 5f) * 5, ps.energyCost(p, ShipClass.LANCER), "about 1.8x");
        assertEquals(ShipClass.BULWARK.s.metal, ps.metalCost(p, ShipClass.BULWARK), "another type is still base price");
        for (int i = 0; i < 20; i++) w.spawn(ShipClass.HARVESTER, p, 900 + i * 15, 1000);
        w.flushPending();
        assertEquals(ShipClass.HARVESTER.s.energy, ps.energyCost(p, ShipClass.HARVESTER), "non-warships never scale");
        // the curve flattens: 10x the copies costs far less than 10x the premium
        assertTrue(1f + spacerts.systems.ProductionSystem.PRICE_K * Math.log(101) < 3f, "logarithmic, not linear");
    }

    @Test
    void stellarRefinementStacksTo999WithLogarithmicPrice() {
        World w = newWorld(83, 1);
        Faction p = w.player;
        Unit ms = p.mothership;
        ShipClass sr = ShipClass.STELLAR_REFINEMENT;
        p.energy = p.metal = 1e7f;
        assertNotNull(w.production.blocker(ms, sr), "endgame only: needs Mothership III");
        ms.tier = 3;
        assertNotNull(w.production.blocker(ms, sr), "and a tier III Research Hub");
        w.spawn(ShipClass.HUB, p, ms.x + 500, ms.y);
        w.flushPending();
        p.techs.add(Tech.HUB_TIER_2);
        p.techs.add(Tech.HUB_TIER_3);
        assertEquals(sr.s.energy, w.production.energyCost(p, sr), "level 1 at base price");
        float h0 = p.harvestMult();
        assertNull(w.production.enqueue(ms, sr));
        assertNotNull(w.production.enqueue(ms, sr), "one level at a time");
        finishQueue(w, ms);
        assertEquals(1, p.refinement);
        assertEquals(h0 * 1.02f, p.harvestMult(), 0.0001f, "+2% mining and energy");
        p.refinement = 100;
        assertEquals(Math.round(sr.s.energy * (1f + (float) Math.log(101)) / 5f) * 5, w.production.energyCost(p, sr), "logarithmic price");
        assertEquals(h0 * 3f, p.harvestMult(), 0.001f, "100 levels = +200%");
        p.refinement = Faction.REFINEMENT_MAX;
        assertNotNull(w.production.blocker(ms, sr), "maxed at 999");
        assertTrue(w.production.energyCost(p, sr) < sr.s.energy * 8, "even level 999 stays under 8x base");
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        assertEquals(Faction.REFINEMENT_MAX, l.player.refinement, "level saved");
    }

    @Test
    void cancelRefundsWhatWasPaid() {
        World w = newWorld(82, 1);
        Faction p = w.player;
        p.energy = p.metal = 10000;
        Unit fac = buildFactory(w, p);
        double e0 = p.energy, m0 = p.metal;
        int first = w.production.energyCost(p, ShipClass.BULWARK);
        assertNull(w.production.enqueue(fac, ShipClass.BULWARK));
        int second = w.production.energyCost(p, ShipClass.BULWARK);
        assertTrue(second > first, "the queued one already counts");
        assertNull(w.production.enqueue(fac, ShipClass.BULWARK));
        assertEquals(e0 - first - second, p.energy, 0.01, "paid the rising prices");
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        Unit lf = null;
        for (Unit u : l.units) if (u.id == fac.id) lf = u;
        assertEquals(second, lf.queue.paidAt(1)[0], "queue prices saved");
        w.production.cancel(fac, 0);
        w.production.cancel(fac, 0);
        assertEquals(e0, p.energy, 0.01, "refunded exactly what was paid");
        assertEquals(m0, p.metal, 0.01);
    }

    // ------------------------------------------------------------------ asteroid showers and giant asteroids

    @Test
    void asteroidsHoldTripleMetalAndClassicMapsHaveGiants() {
        World w = newWorld(91, 1);
        int giants = 0;
        for (Body b : w.bodies) {
            if (b.kind != Body.Kind.ASTEROID) continue;
            if (b.giant) {
                giants++;
                assertTrue(b.maxMetal >= GalaxyGenerator.GIANT_MIN && b.maxMetal <= GalaxyGenerator.GIANT_MAX, "millions of metal");
            } else assertTrue(b.maxMetal >= 1500 && b.maxMetal <= 3600, "3x metal: " + b.maxMetal);
        }
        assertEquals(2, giants, "two giants on a classic map");
    }

    @Test
    void asteroidShowersAddFieldsAndFavourBelts() {
        World w = hive(92);
        spacerts.world.StarSystem belt = null, other = null;
        for (spacerts.world.StarSystem s : w.galaxy.systems) {
            if (belt == null && s.archetype == spacerts.world.SystemArchetype.BELT) belt = s;
            if (other == null && s.archetype == spacerts.world.SystemArchetype.NURSERY) other = s;
        }
        assertEquals(1f / 20f, spacerts.systems.EconomySystem.SHOWER_CHANCE_BELT, 1e-6);
        assertEquals(1f / 60f, spacerts.systems.EconomySystem.SHOWER_CHANCE, 1e-6);
        int before = 0;
        for (Body b : other.bodies) if (b.kind == Body.Kind.ASTEROID) before++;
        assertTrue(w.economy.asteroidShower(other), "a shower lands");
        run(w, 0.1f);
        int after = 0;
        for (Body b : other.bodies) if (b.kind == Body.Kind.ASTEROID) after++;
        assertTrue(after >= before + 5, "a fresh field: " + before + " -> " + after);
        // over a long stretch, belts collect far more showers than other hexes
        World h = hive(93);
        int beltHexes = 0, otherHexes = 0;
        java.util.Map<spacerts.world.StarSystem, Integer> start = new java.util.HashMap<>();
        for (spacerts.world.StarSystem s : h.galaxy.systems) {
            int n = 0;
            for (Body b : s.bodies) if (b.kind == Body.Kind.ASTEROID) n++;
            start.put(s, n);
        }
        run(h, 60 * 60);
        float beltGain = 0, otherGain = 0;
        for (spacerts.world.StarSystem s : h.galaxy.systems) {
            int n = 0;
            for (Body b : s.bodies) if (b.kind == Body.Kind.ASTEROID && !b.dead) n++;
            if (s.archetype == spacerts.world.SystemArchetype.BELT) { beltGain += n - start.get(s); beltHexes++; }
            else { otherGain += n - start.get(s); otherHexes++; }
        }
        System.out.printf("asteroids gained per hex in an hour: belts %.1f, others %.1f%n", beltGain / beltHexes, otherGain / otherHexes);
        assertTrue(beltGain / beltHexes > otherGain / otherHexes, "belts get more showers");
    }

    @Test
    void giantAsteroidDrainsPreciselyAndSaves() {
        World w = newWorld(94, 1);
        Faction p = w.player;
        Body giant = null;
        for (Body b : w.bodies) if (b.giant) giant = b;
        assertNotNull(giant);
        Unit miner = w.spawn(ShipClass.MINER, p, giant.x + giant.radius + 40, giant.y);
        w.flushPending();
        miner.home = giant;
        miner.body = giant;
        double m0 = giant.metal, p0 = p.metal;
        run(w, 60);
        double taken = m0 - giant.metal;
        assertEquals(spacerts.systems.ConstructionSystem.MINER_RATE * 60, taken, 20, "a miner's tiny bites still register on millions");
        assertTrue(p.metal - p0 >= taken - 20, "and reach the stockpile");
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        Body lg = null;
        for (Body b : l.bodies) if (b.giant) { if (lg == null || Math.abs(b.metal - giant.metal) < Math.abs(lg.metal - giant.metal)) lg = b; }
        assertNotNull(lg, "giant saved");
        assertEquals(giant.metal, lg.metal, 1, "metal saved to the unit");
    }

    // ------------------------------------------------------------------ shields, housing, roaming miners, patrols, new labs

    @Test
    void shieldsRecoverSlowerButColoniesAreTougher() {
        World w = newWorld(101, 1);
        Faction p = w.player;
        Unit b = w.spawn(ShipClass.BULWARK, p, 500, 500);
        w.flushPending();
        assertEquals(ShipClass.BULWARK.s.shieldRegen * Unit.DURABILITY * 0.5f, b.shieldRegen(), 0.01f, "half-speed recovery");
        Body col = colonizeNearest(w, p);
        assertEquals(ShipClass.COLONY.s.shield * Unit.DURABILITY * 5f, col.colony.maxShield(), 0.01f, "5x shield on conquered planets");
    }

    @Test
    void shipsCostOneMoreHousingExceptInfantry() {
        assertEquals(2, ShipClass.HARVESTER.s.pop);
        assertEquals(5, ShipClass.BULWARK.s.pop);
        assertEquals(21, ShipClass.LEVIATHAN.s.pop);
        assertEquals(1, ShipClass.LANCER.s.pop, "infantry unchanged");
        assertEquals(1, ShipClass.PATROL.s.pop, "Patrol Ship is 1 housing");
        assertEquals(0, ShipClass.MENDER.s.pop, "structures stay free");
        assertEquals(35, World.BASE_HOUSING);
    }

    @Test
    void minerCrawlsToTheNextClump() {
        World w = newWorld(102, 1);
        Faction p = w.player;
        // a spot with no asteroid within mining reach but one a short crawl away
        float sx = -1, sy = -1;
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 20000 && sx < 0; i++) {
            float x = 1000 + r.nextFloat() * 10000, y = 1000 + r.nextFloat() * 10000;
            float near = Float.MAX_VALUE;
            for (Body b : w.bodies) if (!b.dead && b.kind == Body.Kind.ASTEROID) near = Math.min(near, (float) Math.hypot(b.x - x, b.y - y) - b.radius);
            if (near > spacerts.systems.ConstructionSystem.MINER_REACH + 60 && near < spacerts.systems.ConstructionSystem.MINER_REACH + 260) { sx = x; sy = y; }
        }
        assertTrue(sx > 0, "found a test spot");
        Unit m = w.spawn(ShipClass.MINER, p, sx, sy);
        w.flushPending();
        run(w, 1);
        assertTrue(m.relocating, "field empty: relocating");
        double m0 = p.metal;
        float x0 = m.x, y0 = m.y;
        run(w, 40);
        assertTrue(Math.hypot(m.x - x0, m.y - y0) > 50, "crawled toward the next clump");
        assertFalse(m.relocating, "arrived");
        assertNotNull(m.body, "mining again");
        assertTrue(p.metal > m0, "metal flowing");
    }

    @Test
    void patrolShipPatrolsAndSplits() {
        World w = newWorld(103, 1);
        Faction p = w.player;
        p.energy = p.metal = 5000;
        Unit ms = p.mothership;
        assertNotNull(w.production.enqueue(ms, ShipClass.PATROL), "needs Mothership tier II");
        ms.tier = 2;
        assertNull(w.production.enqueue(ms, ShipClass.PATROL));
        assertTrue(ShipClass.PATROL.s.speed > ShipClass.LANCER.s.speed, "faster than infantry");
        assertEquals(ShipClass.LANCER.s.hp * 2, ShipClass.PATROL.s.hp, 0.01, "twice infantry health");
        assertTrue(ShipClass.PATROL.s.dps() < ShipClass.LANCER.s.dps(), "less damage");
        Body col = colonizeNearest(w, p);
        Unit pat = w.spawn(ShipClass.PATROL, p, ms.x + 200, ms.y);
        w.flushPending();
        boolean visitedColony = false, backHome = false;
        for (int i = 0; i < 240 * 30 && !(visitedColony && backHome); i++) {
            w.update(1f / 30f);
            if (pat.dist(col.x, col.y) < col.radius + 260) visitedColony = true;
            if (visitedColony && pat.dist(ms) < ms.radius + 260) backHome = true;
        }
        assertTrue(visitedColony, "patrols the colony");
        assertTrue(backHome, "and back to the Mothership");
        pat.kill(false);
        run(w, 0.1f);
        assertEquals(2, w.count(p, ShipClass.PATROL_SKIFF), "splits into two skiffs");
        p.techs.add(Tech.SPLINTER_HULLS);
        Unit pat2 = w.spawn(ShipClass.PATROL, p, ms.x + 200, ms.y);
        w.flushPending();
        pat2.kill(false);
        run(w, 0.1f);
        assertEquals(5, w.count(p, ShipClass.PATROL_SKIFF), "Splinter Hulls: three more");
    }

    @Test
    void shockwaveStunsAttackers() {
        World w = newWorld(104, 1);
        Faction p = w.player;
        p.techs.add(Tech.MARTYR_SHOCKWAVE);
        Body col = colonizeNearest(w, p);
        Unit raider = w.spawn(ShipClass.RAIDER, w.pirates, col.x + 400, col.y);
        Unit far = w.spawn(ShipClass.RAIDER, w.pirates, col.x + 3000, col.y);
        w.flushPending();
        col.colony.kill(false);
        run(w, 0.1f);
        assertTrue(raider.stunTimer > 7, "stunned");
        assertEquals(0, far.stunTimer, 0.001, "out of range");
        raider.command(spacerts.units.Order.MOVE, raider.x + 2000, raider.y);
        float x0 = raider.x;
        run(w, 3);
        assertEquals(x0, raider.x, 0.01, "frozen while stunned");
        run(w, 8);
        assertTrue(raider.x > x0 + 50, "moves again once it wears off");
    }

    @Test
    void sniperDeflectorsProtectPlanets() {
        World w = newWorld(105, 1);
        Faction p = w.player;
        Body col = colonizeNearest(w, p);
        Unit sniper = w.spawn(ShipClass.LONGBOW, w.factions.get(1), col.x + 800, col.y);
        w.flushPending();
        Unit c = col.colony;
        float before = c.hp + c.shield;
        w.combat.applyDamage(c, 1000, true, sniper);
        float plain = before - (c.hp + c.shield);
        p.techs.add(Tech.SNIPER_DEFLECTORS);
        before = c.hp + c.shield;
        w.combat.applyDamage(c, 1000, true, sniper);
        float deflected = before - (c.hp + c.shield);
        assertEquals(plain * 0.65f, deflected, 1f, "35% less sniper damage on planets");
    }

    @Test
    void counterBatteryOnlyShootsRevealedSnipers() {
        World w = newWorld(106, 1);
        Faction p = w.player;
        Body col = colonizeNearest(w, p);
        w.spawnPlanetTurrets(col, p);
        w.flushPending();
        // only the colony and its turrets keep watch: other ships' sensors would spot the sniper
        for (Unit u : w.units) if (u.owner == p && (u.s.harvester || u.cls.isWarship())) u.kill(true);
        run(w, 0.1f);
        // the turret on the far side of the planet from the Mothership, sniper straight out beyond it
        Unit turret = null;
        Unit ms = p.mothership;
        for (Unit u : w.units)
            if (u.cls == ShipClass.TURRET && u.owner == p && u.home == col && (turret == null || u.dist(ms) > turret.dist(ms))) turret = u;
        assertNotNull(turret);
        float ox = turret.x - col.x, oy = turret.y - col.y, len = (float) Math.hypot(ox, oy);
        Unit sniper = w.spawn(ShipClass.LONGBOW, w.factions.get(1), turret.x + ox / len * 1000, turret.y + oy / len * 1000);
        w.flushPending();
        sniper.hp = 1e6f;
        sniper.stunTimer = 1e6f; // hold it still and silent: this test is only about visibility
        p.techs.add(Tech.COUNTER_BATTERY);
        run(w, 2);
        assertFalse(w.canSee(p, sniper), "sniper is out of sight");
        assertNull(turret.target, "hidden sniper: no shot");
        w.pings.add(new spacerts.world.Ping(p, sniper.x, sniper.y, 150, 5)); // the sniper fired and gave itself away
        float hp0 = sniper.hp;
        run(w, 3);
        assertSame(sniper, turret.target, "revealed: counter-battery fire");
        assertTrue(sniper.hp + sniper.shield < hp0 + sniper.maxShield(), "hit at 1000 range");
    }

    @Test
    void refitThrustersSalvageAndInhibitors() {
        World w = newWorld(107, 1);
        Faction p = w.player;
        // Rapid Refit Docks: twice the base repair
        Unit a = w.spawn(ShipClass.BULWARK, p, p.mothership.x + 150, p.mothership.y);
        w.flushPending();
        a.hp = a.maxHp() * 0.5f;
        a.retreatOff = true;
        run(w, 8);
        float plain = a.hp;
        a.hp = a.maxHp() * 0.5f;
        p.techs.add(Tech.RAPID_REFIT);
        run(w, 8);
        assertTrue(a.hp - a.maxHp() * 0.5f > (plain - a.maxHp() * 0.5f) * 1.8f, "refit repairs about twice as much");
        // Emergency Thrusters: retreating ships are faster
        Unit r = w.spawn(ShipClass.BULWARK, p, 2000, 2000);
        w.flushPending();
        r.retreating = true;
        r.command(spacerts.units.Order.MOVE, 4000, 2000);
        r.retreating = true;
        run(w, 2);
        float x1 = r.x;
        p.techs.add(Tech.EMERGENCY_THRUSTERS);
        run(w, 2);
        assertTrue(r.x - x1 > (x1 - 2000) * 1.3f, "thrusters: faster retreat");
        // Jump Inhibitors (Hive): enemies arriving in an owned hex are slowed
        World h = hive(108);
        Faction hp = h.player;
        Unit beacon = h.spawn(ShipClass.BEACON, hp, h.galaxy.home().cx + 600, h.galaxy.home().cy);
        h.flushPending();
        run(h, 2.5f);
        assertTrue(h.galaxy.home().owned());
        hp.techs.add(Tech.JUMP_INHIBITORS);
        Body gateIn = null;
        for (Body g : h.galaxy.home().gates) if (g != null) gateIn = g.link; // a gate in a neighbour leading home
        Unit raider = h.spawn(ShipClass.RAIDER, h.pirates, gateIn.x, gateIn.y);
        h.flushPending();
        h.movement.jump(raider, gateIn);
        assertSame(h.galaxy.home(), h.systemAt(raider.x, raider.y));
        assertTrue(raider.slowTimer > 7, "slowed on arrival");
        assertFalse(beacon.dead);
    }

    // ------------------------------------------------------------------ EVE-scale hexes and warp cruise

    @Test
    void hexesAreThreeTimesWiderWithMoreContent() {
        World w = hive(111);
        assertEquals(10800f, spacerts.world.Galaxy.HEX_R, 0.01f, "3x the old 3600 radius");
        spacerts.world.StarSystem home = w.galaxy.home();
        for (Body g : home.gates) if (g != null) assertTrue(home.dist(g.x, g.y) > 8000, "gates ~3x further out (was ~2700)");
        float rocks = 0, stars = 0, planets = 0;
        for (spacerts.world.StarSystem s : w.galaxy.systems)
            for (Body b : s.bodies) {
                if (b.kind == Body.Kind.ASTEROID) rocks++;
                if (b.kind == Body.Kind.STAR) stars++;
                if (b.kind == Body.Kind.PLANET) planets++;
            }
        int n = w.galaxy.systems.size();
        System.out.printf("per hex: %.1f asteroids, %.1f stars, %.1f planets; %d bodies, %d units%n",
                rocks / n, stars / n, planets / n, w.bodies.size(), w.units.size());
        assertTrue(rocks / n >= 16.2f * 2.5f, "about 3x the asteroids (was 16.2 per hex)");
        for (Body b : w.bodies) assertTrue(b.system.contains(b.x, b.y), "everything inside its hex");
    }

    @Test
    void warpCruiseKicksInOnLongTripsOnly() {
        World w = hive(112);
        Faction p = w.player;
        spacerts.world.StarSystem home = w.galaxy.home();
        Unit far = w.spawn(ShipClass.BULWARK, p, home.cx - 6000, home.cy);
        Unit near = w.spawn(ShipClass.BULWARK, p, home.cx - 6000, home.cy + 800);
        w.flushPending();
        far.command(spacerts.units.Order.MOVE, home.cx + 6000, home.cy);
        near.command(spacerts.units.Order.MOVE, home.cx - 4500, home.cy + 800);
        run(w, 1);
        assertFalse(far.warping, "still spooling");
        run(w, 1.5f);
        assertTrue(far.warping, "warp engaged after the spool-up");
        assertFalse(near.warping, "short trips never warp");
        float x0 = far.x;
        run(w, 1);
        float warpSpeed = far.x - x0;
        assertTrue(warpSpeed > ShipClass.BULWARK.s.speed * 3f, "about 4x speed in warp: " + warpSpeed);
        far.lastHit = w.time; // taking fire drops it out
        run(w, 0.1f);
        assertFalse(far.warping, "combat drops out of warp");
    }

    @Test
    void oldHiveSaveIsRejected() {
        World w = hive(113);
        String json = SaveManager.toJson(w).replace("\"hexR\":10800", "\"hexR\":0");
        assertThrows(IllegalStateException.class, () -> SaveManager.fromJson(json), "old small-layout saves are refused");
        assertNotNull(SaveManager.fromJson(SaveManager.toJson(newWorld(114, 1))), "classic saves unaffected");
    }
}
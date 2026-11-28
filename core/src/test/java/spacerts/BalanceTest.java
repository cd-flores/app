package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.ai.RivalAI;
import spacerts.net.Lockstep;
import spacerts.systems.ConstructionSystem;
import spacerts.systems.ProductionSystem;
import spacerts.units.ShipClass;
import spacerts.units.Spec;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.PlanetType;
import spacerts.world.World;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The balance pass: ship stats, prices, limits, finite resources, passive fortified worlds and bot pacing. */
class BalanceTest {

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

    private static Unit harvester(World w, Faction f) {
        for (Unit u : w.units) if (!u.dead && u.owner == f && u.cls == ShipClass.HARVESTER) return u;
        throw new IllegalStateException("no harvester");
    }

    @Test
    void shipStatsHousingAndPrices() {
        // snipers lose 50 range; Bulwarks and snipers keep up with Lancers
        assertEquals(850, ShipClass.LONGBOW.s.range);
        assertEquals(950, ShipClass.PHANTOM.s.range);
        for (ShipClass c : new ShipClass[]{ShipClass.BULWARK, ShipClass.LONGBOW, ShipClass.PHANTOM})
            assertEquals(ShipClass.LANCER.s.speed, c.s.speed, c.s.name + " keeps up with Lancers");
        // housing: snipers one less, Bulwark one more
        assertEquals(3, ShipClass.LONGBOW.s.pop);
        assertEquals(4, ShipClass.PHANTOM.s.pop);
        assertEquals(6, ShipClass.BULWARK.s.pop);
        // +50 energy on everything that costs something; the Repair Station costs 5x
        assertEquals(120 + Spec.ENERGY_SURCHARGE, ShipClass.LANCER.s.energy);
        assertEquals(120, ShipClass.LANCER.s.metal, "metal unchanged");
        assertEquals(750 + Spec.ENERGY_SURCHARGE, ShipClass.MENDER.s.energy);
        assertEquals(750, ShipClass.MENDER.s.metal);
        assertEquals(0, ShipClass.DRONE.s.energy, "things that are never bought stay free");
        assertEquals(200 + Spec.ENERGY_SURCHARGE, spacerts.research.Tech.HARVEST_1.energy, "research too");
        // Bulwarks and snipers get pricier twice as fast
        assertEquals(2 * ProductionSystem.PRICE_K, ProductionSystem.priceK(ShipClass.BULWARK), 1e-6);
        assertEquals(ProductionSystem.HEAVY_PRICE_K, ProductionSystem.priceK(ShipClass.LONGBOW), 1e-6);
        assertEquals(ProductionSystem.HEAVY_PRICE_K, ProductionSystem.priceK(ShipClass.PHANTOM), 1e-6);
        assertEquals(ProductionSystem.PRICE_K, ProductionSystem.priceK(ShipClass.LANCER), 1e-6);
    }

    @Test
    void heavyPricesClimbFaster() {
        World w = make();
        Faction p = w.player;
        float x = p.mothership.x + 400, y = p.mothership.y;
        for (int i = 0; i < 3; i++) {
            spawn(w, ShipClass.BULWARK, p, x + i * 50, y);
            spawn(w, ShipClass.LANCER, p, x + i * 50, y + 100);
        }
        float bulwark = w.production.priceMult(p, ShipClass.BULWARK) - 1, lancer = w.production.priceMult(p, ShipClass.LANCER) - 1;
        assertTrue(bulwark > lancer * 1.9f, "Bulwark markup " + bulwark + " vs Lancer " + lancer);
    }

    @Test
    void halcyonIsGoneButTheRepairStationStays() {
        World w = make();
        Faction p = w.player;
        Unit st = spawn(w, ShipClass.FACTORY, p, p.mothership.x + 400, p.mothership.y);
        assertNotNull(w.production.blocker(st, ShipClass.HALCYON), "no more Halcyons");
        assertNull(w.construction.baseBlocker(p, ShipClass.MENDER), "Repair Stations can still be built");
    }

    @Test
    void atMostTwoShipStations() {
        World w = make();
        Faction p = w.player;
        Unit ms = p.mothership;
        assertEquals(2, ConstructionSystem.MAX_STATIONS);
        spawn(w, ShipClass.FACTORY, p, ms.x + 400, ms.y);
        assertNull(w.construction.baseBlocker(p, ShipClass.FACTORY), "a second station is fine");
        spawn(w, ShipClass.FACTORY, p, ms.x - 400, ms.y);
        assertNotNull(w.construction.baseBlocker(p, ShipClass.FACTORY), "no third station");
        spawn(w, ShipClass.HUB, p, ms.x, ms.y + 400);
        assertNotNull(w.construction.baseBlocker(p, ShipClass.HUB), "one Research Hub");
    }

    @Test
    void shipsNoLongerRepairBesideColonies() {
        World w = make();
        Faction p = w.player;
        for (Unit u : w.units) if (u.owner.kind == Faction.Kind.PIRATES) u.kill(true);
        float x = Galaxy.CLASSIC_SIZE * 0.5f, y = Galaxy.CLASSIC_SIZE * 0.3f;
        spawn(w, ShipClass.COLONY, p, x, y);
        Unit b = spawn(w, ShipClass.BULWARK, p, x + 120, y);
        b.retreatOff = true;
        b.hp = b.maxHp() * 0.5f;
        p.metal = 100000;
        float hp0 = b.hp;
        run(w, 12);
        assertEquals(hp0, b.hp, 0.01, "no repair beside a colony");
        spawn(w, ShipClass.FACTORY, p, x + 120, y + 140);
        run(w, 12);
        assertTrue(b.hp > hp0, "a Ship Station still repairs");
    }

    @Test
    void fortifiedWorldsLetFleetsPassUntilAttacked() {
        World w = make();
        Faction p = w.player;
        Body fort = null;
        for (Body b : w.bodies) if (b.isPlanet() && b.type == PlanetType.DEFENDED && b.guarded()) { fort = b; break; }
        assertNotNull(fort, "a fortified world");
        run(w, 1); // guards spawn
        // an unarmed ship parks right inside the guard ring
        Unit passer = spawn(w, ShipClass.COLONY_SHIP, p, fort.x + fort.radius + 90, fort.y);
        passer.retreatOff = true;
        float hp0 = passer.hp, sh0 = passer.shield;
        run(w, 4);
        assertEquals(hp0, passer.hp, 0.01, "left alone");
        assertEquals(sh0, passer.shield, 0.01);
        for (Unit t : fort.turrets) assertNotSame(passer, t.target);
        // once the faction hits the planet, the natives fight back
        fort.provoke(p, w.time);
        assertTrue(fort.provokedBy(p, w.time));
        run(w, 4);
        assertTrue(passer.dead || passer.hp + passer.shield < hp0 + sh0, "attacked after provoking");
        // and they forget after a while
        assertFalse(fort.provokedBy(p, w.time + Body.PROVOKE_MEMORY + 1));
    }

    @Test
    void starsRunDryAndBurnOut() {
        World w = make();
        Faction p = w.player;
        Body star = null;
        for (Body b : w.bodies) if (b.kind == Body.Kind.STAR) { star = b; break; }
        assertNotNull(star);
        assertTrue(star.finiteStar(), "stars hold a finite deposit");
        assertTrue(star.maxMetal >= GalaxyGenerator.STAR_MIN && star.maxMetal <= GalaxyGenerator.STAR_MAX);
        Unit solar = spawn(w, ShipClass.SOLAR, p, star.x + star.radius + 40, star.y);
        solar.home = star;
        star.metal = 20; // nearly spent
        run(w, 6);
        assertTrue(star.burntOut(), "drained by the collector");
        assertFalse(star.harvestable(), "harvesters can't use it any more");
        Unit h = harvester(w, p);
        String why = w.construction.blocker(h, ShipClass.SOLAR, star, star.x + star.radius + 60, star.y + 60);
        assertNotNull(why);
        assertTrue(why.contains("burnt out"), why);
    }

    @Test
    void giantAsteroidsSitOnAllFourSidesAndAreFinite() {
        World w = make();
        float S = Galaxy.CLASSIC_SIZE;
        Set<String> sides = new HashSet<>();
        for (Body b : w.bodies) {
            if (!b.giant) continue;
            assertTrue(b.maxMetal <= GalaxyGenerator.GIANT_MAX && b.maxMetal >= GalaxyGenerator.GIANT_MIN);
            float dn = b.y, ds = S - b.y, dw = b.x, de = S - b.x;
            float m = Math.min(Math.min(dn, ds), Math.min(dw, de));
            assertTrue(m < S * 0.25f, "near an edge: " + b.x + "," + b.y);
            sides.add(m == dn ? "N" : m == ds ? "S" : m == dw ? "W" : "E");
        }
        assertEquals(Set.of("N", "S", "E", "W"), sides);
    }

    @Test
    void asteroidClumpsAreBigger() {
        assertEquals(2, GalaxyGenerator.CLUMP_MULT);
        World w = make();
        int asteroids = 0;
        for (Body b : w.bodies) if (b.kind == Body.Kind.ASTEROID && !b.giant) asteroids++;
        assertTrue(asteroids > 300, "plenty of asteroids: " + asteroids);
    }

    @Test
    void botsStartLaterExpandSlowlyAndThinkAtTheirOwnPace() {
        assertEquals(480f, RivalAI.START_DELAY, "bots wait 8 minutes");
        World w = make();
        RivalAI ai = rival(w).ai;
        w.time = 0;
        assertEquals(3, ai.colonyLimit());
        w.time = 15 * 60;
        assertEquals(5, ai.colonyLimit());
        w.time = 45 * 60;
        assertEquals(9, ai.colonyLimit());
        w.time = 10 * 3600;
        assertEquals(14, ai.colonyLimit(), "never above 14");
        assertEquals(20f, Difficulty.EASY.thinkInterval());
        assertTrue(Difficulty.NORMAL.thinkInterval() < Difficulty.EASY.thinkInterval());
        assertTrue(Difficulty.HARD.thinkInterval() < Difficulty.NORMAL.thinkInterval());
        assertTrue(Difficulty.INSANE.thinkInterval() < Difficulty.HARD.thinkInterval());
    }
}

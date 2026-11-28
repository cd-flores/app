package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.net.Lockstep;
import spacerts.research.Tech;
import spacerts.save.SaveManager;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.MatchStats;
import spacerts.world.World;

import static org.junit.jupiter.api.Assertions.*;

/** End-of-match stats: kills and losses, building, research, colonies, income and spending, army, saves. */
class MatchStatsTest {

    private static World make() {
        World w = new World(GameSetup.skirmish(23, 1, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        for (Unit u : w.units) if (u.owner.kind == Faction.Kind.PIRATES || u.owner.kind == Faction.Kind.NATIVES) u.kill(true);
        run(w, 0.1f);
        return w;
    }

    private static void run(World w, float seconds) {
        int n = (int) Math.ceil(seconds / Lockstep.STEP);
        for (int i = 0; i < n; i++) w.update(Lockstep.STEP);
    }

    private static Faction rival(World w) {
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) return f;
        throw new AssertionError("no rival");
    }

    private static Unit spawn(World w, ShipClass c, Faction f, float x, float y) {
        Unit u = w.spawn(c, f, x, y);
        w.flushPending();
        return u;
    }

    @Test
    void killsAndLossesAreCredited() {
        World w = make();
        Faction p = w.player, r = rival(w);
        float x = p.mothership.x + 3000, y = p.mothership.y;
        Unit gun = spawn(w, ShipClass.BULWARK, p, x, y);
        Unit prey = spawn(w, ShipClass.WISP, r, x + 50, y);
        w.combat.applyDamage(prey, 1e6f, true, gun);
        run(w, 0.1f);
        assertTrue(prey.dead);
        assertEquals(1, p.stats.kills);
        assertEquals(1, r.stats.shipsLost);
        assertTrue(p.stats.damageDealt > 0 && p.stats.damageDealt <= prey.maxHp() + prey.s.shield + 1, "damage capped at what it had");

        Unit beacon = spawn(w, ShipClass.BEACON, r, x + 80, y);
        w.combat.applyDamage(beacon, 1e7f, true, gun);
        run(w, 0.1f);
        assertEquals(1, p.stats.structuresDestroyed);
        assertEquals(1, r.stats.structuresLost);
    }

    @Test
    void silentDeathsAndScuttlesDontCount() {
        World w = make();
        Faction p = w.player;
        Unit u = spawn(w, ShipClass.LANCER, p, p.mothership.x + 400, p.mothership.y);
        u.kill(true);
        run(w, 0.1f);
        assertEquals(0, p.stats.shipsLost);
    }

    @Test
    void buildingResearchAndColoniesAreCounted() {
        World w = make();
        Faction p = w.player;
        p.energy = p.metal = 5000;
        assertNull(w.production.enqueue(p.mothership, ShipClass.HARVESTER));
        run(w, ShipClass.HARVESTER.s.buildTime + 3);
        assertEquals(1, p.stats.shipsBuilt);

        Unit h = null;
        for (Unit u : w.units) if (!u.dead && u.owner == p && u.cls.isBuilder()) h = u;
        float[] site = null;
        for (int i = 0; i < 400 && site == null; i++) {
            float a = i * 0.7f, d = 300 + (i % 10) * 40;
            float sx = p.mothership.x + (float) Math.cos(a) * d, sy = p.mothership.y + (float) Math.sin(a) * d;
            if (w.construction.siteBlocker(p, sx, sy, ShipClass.FACTORY.s.radius) == null) site = new float[]{sx, sy};
        }
        assertNotNull(site);
        assertNull(w.construction.order(h, ShipClass.FACTORY, null, site[0], site[1]));
        for (int i = 0; i < 120 * 30 && p.stats.structuresBuilt == 0; i++) w.update(Lockstep.STEP);
        assertEquals(1, p.stats.structuresBuilt);

        w.production.complete(p, Tech.ENGINES);
        w.production.complete(p, Tech.ENGINES); // already known: not counted twice
        assertEquals(1, p.stats.techs);

        Body world = null;
        for (Body b : w.bodies) if (b.colonizable() && !b.colonized()) { world = b; break; }
        assertNotNull(world);
        w.colonize(world, p);
        assertEquals(1, p.stats.colonies);
    }

    @Test
    void spendingIsNetOfRefundsAndIncomeIsGathered() {
        World w = make();
        Faction p = w.player;
        p.energy = p.metal = 5000;
        double e0 = p.stats.energySpent;
        int[] none = new int[spacerts.research.RareItem.COUNT];
        p.pay(300, 200, none);
        assertEquals(e0 + 300, p.stats.energySpent, 1e-6);
        p.refund(300, 200, none);
        assertEquals(e0, p.stats.energySpent, 1e-6);
        double before = p.energy, g0 = p.stats.energyGathered;
        p.earn(40, 0);
        assertEquals(before + 40, p.energy, 1e-6);
        assertEquals(g0 + 40, p.stats.energyGathered, 1e-6);

        run(w, 90); // harvesters start mining on their own
        assertTrue(p.stats.gathered() > 0, "mining counted");
    }

    @Test
    void armyIsSampledAndPeakKept() {
        World w = make();
        Faction p = w.player;
        Unit b = spawn(w, ShipClass.BULWARK, p, p.mothership.x + 400, p.mothership.y);
        run(w, 1.2f);
        assertTrue(p.stats.armyNow >= ShipClass.BULWARK.s.energy + ShipClass.BULWARK.s.metal);
        double peak = p.stats.peakArmy;
        b.kill(true);
        run(w, 1.2f);
        assertTrue(p.stats.armyNow < peak);
        assertEquals(peak, p.stats.peakArmy, 1e-6, "peak kept");
    }

    @Test
    void eliminationTimeAndNoLossesAfterIt() {
        World w = make();
        Faction r = rival(w);
        run(w, 5);
        r.mothership.kill(false);
        run(w, 0.2f);
        assertTrue(r.eliminated);
        assertTrue(r.stats.eliminatedAt > 4);
        assertEquals(1, r.stats.shipsLost + r.stats.structuresLost, "only the flagship counts; the rest went down with the empire");
    }

    @Test
    void statsSurviveASave() {
        World w = make();
        Faction p = w.player;
        p.stats.kills = 7;
        p.stats.metalGathered = 1234.5;
        p.stats.eliminatedAt = -1;
        World l = SaveManager.fromJson(SaveManager.toJson(w));
        MatchStats s = l.player.stats;
        assertEquals(7, s.kills);
        assertEquals(1234.5, s.metalGathered, 1e-6);
        assertEquals(-1, s.eliminatedAt, 1e-6);
    }
}

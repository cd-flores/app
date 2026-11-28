package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.net.Command;
import spacerts.net.CommandExecutor;
import spacerts.net.Lockstep;
import spacerts.systems.EconomySystem;
import spacerts.systems.StationRally;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Rally Stations, the Defender, paid repairs and upkeep. */
class GameplayTest {

    private static World make() {
        World w = new World(GameSetup.skirmish(91, 1, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        return w;
    }

    private static void run(World w, float seconds) {
        int n = (int) (seconds / Lockstep.STEP);
        for (int i = 0; i < n; i++) w.update(Lockstep.STEP);
    }

    /** Removes everything the player owns except the Mothership, so nothing else moves, earns or pushes. */
    private static void strip(World w, Faction p) {
        for (Unit u : w.units) if (u.owner == p && u != p.mothership) u.kill(true);
        run(w, 0.1f);
    }

    private static Unit spawn(World w, ShipClass c, Faction f, float x, float y) {
        Unit u = w.spawn(c, f, x, y);
        w.flushPending();
        return u;
    }

    private static Faction rival(World w) {
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) return f;
        throw new IllegalStateException("no rival");
    }

    // ------------------------------------------------------------------ rally stations

    @Test
    void rallyStationsFormHexRingsAndKeepUpWithTheMothership() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit[] st = {
                spawn(w, ShipClass.FACTORY, p, ms.x + 500, ms.y + 300),
                spawn(w, ShipClass.HUB, p, ms.x - 400, ms.y + 200),
                spawn(w, ShipClass.MENDER, p, ms.x + 100, ms.y - 450),
        };
        CommandExecutor.apply(w, new Command(Command.Type.RALLY_STATIONS, p.id).arg(1));
        assertTrue(p.rallyStations);
        run(w, 40);
        Set<String> slots = new HashSet<>();
        for (Unit s : st) {
            assertTrue(s.rallied, s.s.name + " joins the rally");
            assertTrue(s.dist(s.slotX, s.slotY) < 40, s.s.name + " at its slot: " + s.dist(s.slotX, s.slotY));
            assertEquals(StationRally.SPACING, s.dist(ms), 25, "first hex ring around the Mothership");
            slots.add((int) s.slotX + "," + (int) s.slotY);
        }
        assertEquals(3, slots.size(), "distinct slots");

        // the Mothership travels: the formation keeps pace at its speed
        float dir = ms.x < spacerts.world.Galaxy.CLASSIC_SIZE / 2 ? 1 : -1; // toward the middle of the map
        float tx = ms.x + dir * 1400, ty = ms.y;
        ms.command(Order.MOVE, tx, ty);
        run(w, 20);
        for (Unit s : st) assertTrue(s.dist(s.slotX, s.slotY) < 120, s.s.name + " keeps up while moving: " + s.dist(s.slotX, s.slotY));
        run(w, 60);
        assertTrue(ms.dist(tx, ty) < ms.radius + 20, "mothership arrived: " + ms.dist(tx, ty));
        for (Unit s : st) assertTrue(s.dist(s.slotX, s.slotY) < 40, s.s.name + " back in formation");

        // a move order to a rallied station is ignored until Rally is off
        CommandExecutor.apply(w, new Command(Command.Type.ORDER, p.id).unit(st[0]).at(ms.x - dir * 2000, ms.y));
        run(w, 5);
        assertTrue(st[0].dist(st[0].slotX, st[0].slotY) < 40, "stays in formation");

        // Rally off: stations stop where they are and crawl at half speed again
        CommandExecutor.apply(w, new Command(Command.Type.RALLY_STATIONS, p.id).arg(0));
        run(w, 0.1f);
        assertFalse(st[0].rallied);
        assertEquals(ms.speed() / 2f, st[0].speed(), 0.01);
    }

    @Test
    void stationsAreNotWarships() {
        for (ShipClass c : new ShipClass[]{ShipClass.FACTORY, ShipClass.HUB, ShipClass.MENDER}) {
            assertTrue(c.isStation());
            assertTrue(c.isBuilding());
            assertFalse(c.isWarship(), c + " is no army ship");
            assertFalse(c.s.stationary, c + " can move");
            assertEquals(14, c.s.speed, 0.01);
        }
        assertEquals("Ship Station", ShipClass.FACTORY.s.name);
        float[] first = StationRally.slot(0);
        assertEquals(StationRally.SPACING, Math.hypot(first[0], first[1]), 0.5);
        assertEquals(1, StationRally.ringOf(5));
        assertEquals(2, StationRally.ringOf(6), "ring 1 holds 6");
        assertEquals(2, StationRally.ringOf(17));
        assertEquals(3, StationRally.ringOf(18), "ring 2 holds 12");
    }

    // ------------------------------------------------------------------ defender

    @Test
    void defenderNeedsTierTwoAndIsCappedAtFour() {
        World w = make();
        Faction p = w.player;
        Unit ms = p.mothership;
        assertTrue(w.production.blocker(ms, ShipClass.DEFENDER).contains("tier II"));
        ms.tier = 2;
        for (int i = 0; i < 4; i++) spawn(w, ShipClass.DEFENDER, p, ms.x + 400, ms.y + i * 100);
        String why = w.production.blocker(ms, ShipClass.DEFENDER);
        assertNotNull(why);
        assertTrue(why.contains("limit"), why);
        assertTrue(ShipClass.DEFENDER.isWarship());
        assertTrue(ShipClass.DEFENDER.s.buildTime >= 150, "long build");
        assertTrue(ShipClass.DEFENDER.s.energy + ShipClass.DEFENDER.s.metal >= 5000, "very expensive");
    }

    @Test
    void defenderOrbitsTheMothershipAndShootsIntruders() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit d = spawn(w, ShipClass.DEFENDER, p, ms.x + 300, ms.y);
        float orbit = w.behavior.defenderOrbit(p);
        run(w, 20);
        assertEquals(orbit, d.dist(ms), 60, "settled into orbit");
        ms.command(Order.MOVE, ms.x, ms.y + (ms.y < spacerts.world.Galaxy.CLASSIC_SIZE / 2 ? 1200 : -1200));
        for (int i = 0; i < 6; i++) {
            run(w, 5);
            assertEquals(orbit, d.dist(ms), 120, "orbits the moving Mothership");
        }
        // a move order doesn't pull it away
        CommandExecutor.apply(w, new Command(Command.Type.ORDER, p.id).unit(d).at(ms.x + 4000, ms.y));
        run(w, 10);
        assertEquals(orbit, d.dist(ms), 120, "never leaves its post");

        Unit intruder = spawn(w, ShipClass.BULWARK, rival(w), d.x + 200, d.y);
        float t0 = w.time;
        run(w, 3);
        assertTrue(d.lastFired > t0, "fires at the intruder");
        assertTrue(intruder.dead || intruder.hp < intruder.maxHp() || intruder.shield < intruder.maxShield());
    }

    // ------------------------------------------------------------------ paid repairs

    @Test
    void repairsCostMetalAndStopWithoutIt() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit l = spawn(w, ShipClass.LANCER, p, ms.x + ms.radius + 60, ms.y);
        l.retreatOff = true;
        l.hp = l.maxHp() * 0.3f;
        l.lastHit = -100;
        p.metal = 1000;
        float hp0 = l.hp;
        run(w, 10);
        float restored = l.hp - hp0;
        assertTrue(restored > 0, "repairs at the Mothership");
        float price = World.repairPrice(l);
        assertEquals(World.REPAIR_COST * (ShipClass.LANCER.s.metal / 3f) / l.maxHp(), price, 1e-6, "half the metal price for a full repair");
        assertEquals(1000 - restored * price, p.metal, 0.5, "paid for what was repaired");

        p.metal = 0;
        l.hp = l.maxHp() * 0.3f;
        run(w, 10);
        assertEquals(l.maxHp() * 0.3f, l.hp, 0.01, "no metal, no repairs");
        assertEquals(0, p.metal, 0.001);
    }

    @Test
    void menderRepairsArePaidByTheShipsOwner() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit m = spawn(w, ShipClass.MENDER, p, ms.x + 2500, ms.y);
        Unit a = spawn(w, ShipClass.BULWARK, p, m.x + 80, m.y);
        a.retreatOff = true;
        a.hp = a.maxHp() * 0.5f;
        p.metal = 500;
        run(w, 4);
        assertTrue(a.hp > a.maxHp() * 0.5f, "healed");
        assertTrue(p.metal < 500, "metal spent: " + p.metal);
    }

    // ------------------------------------------------------------------ skills

    private static void use(World w, Unit u, spacerts.units.Ability a) {
        CommandExecutor.apply(w, new Command(Command.Type.ABILITY, u.owner.id).unit(u).arg(a.ordinal()));
    }

    @Test
    void everySkillHasItsOwnKey() {
        Set<Integer> keys = new HashSet<>();
        for (spacerts.units.Ability a : spacerts.units.Ability.values()) {
            if (a == spacerts.units.Ability.NONE) continue;
            assertTrue(keys.add(a.key), a + " shares a key");
            assertEquals(a, spacerts.units.Ability.forKey(a.key));
        }
        assertTrue(ShipClass.WISP.s.has(spacerts.units.Ability.PING) && ShipClass.WISP.s.has(spacerts.units.Ability.GHOST));
        assertTrue(ShipClass.LONGBOW.s.has(spacerts.units.Ability.DASH) && ShipClass.PHANTOM.s.has(spacerts.units.Ability.DASH));
    }

    @Test
    void ghostDriveCloaksAndSpeedsUpButHoldsFire() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        float dir = ms.x < spacerts.world.Galaxy.CLASSIC_SIZE / 2 ? 1 : -1;
        Unit a = spawn(w, ShipClass.WISP, p, ms.x + dir * 300, ms.y + 600);
        Unit b = spawn(w, ShipClass.WISP, p, ms.x + dir * 300, ms.y + 700);
        use(w, a, spacerts.units.Ability.GHOST);
        assertTrue(a.cloaked(w.time), "cloaked");
        assertFalse(a.armed(), "can't fire while ghosting");
        assertFalse(b.cloaked(w.time));
        float ax = a.x, bx = b.x;
        // the same destination, so both get the same travel boost (if any) and only the drive differs
        a.command(Order.MOVE, a.x + dir * 3000, ms.y + 650);
        b.command(Order.MOVE, b.x + dir * 3000, ms.y + 650);
        run(w, 4);
        assertTrue(Math.abs(a.x - ax) > Math.abs(b.x - bx) * 1.4f, "faster: " + Math.abs(a.x - ax) + " vs " + Math.abs(b.x - bx));

        // an enemy right next to it is left alone while the drive is on
        Unit e = spawn(w, ShipClass.LANCER, rival(w), a.x + 60, a.y);
        float t0 = w.time;
        run(w, 1.5f);
        assertTrue(a.lastFired < t0, "holds its fire");
        run(w, spacerts.systems.UnitBehavior.GHOST_TIME);
        assertFalse(a.cloaked(w.time), "the drive wears off");
        assertTrue(a.armed());
        assertTrue(a.abilityCds[spacerts.units.Ability.GHOST.ordinal()] > 0, "recharging");
        assertEquals(0, a.abilityCds[spacerts.units.Ability.PING.ordinal()], 1e-6, "Ping has its own cooldown");
    }

    @Test
    void evasiveDashLeapsAwayFromTheThreat() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        float dir = ms.x < spacerts.world.Galaxy.CLASSIC_SIZE / 2 ? 1 : -1;
        Unit l = spawn(w, ShipClass.LONGBOW, p, ms.x + dir * 1500, ms.y);
        Unit e = spawn(w, ShipClass.LANCER, rival(w), l.x + dir * 120, l.y); // inside its minimum range
        l.target = e;
        float x0 = l.x, y0 = l.y, d0 = l.dist(e);
        use(w, l, spacerts.units.Ability.DASH);
        run(w, spacerts.systems.UnitBehavior.DASH_TIME + 0.05f);
        float moved = l.dist(x0, y0);
        assertEquals(spacerts.systems.UnitBehavior.DASH_DIST, moved, 40, "a 400 dash");
        assertTrue(l.dist(e) > d0 + 300, "away from the threat");
        assertTrue(l.abilityCds[spacerts.units.Ability.DASH.ordinal()] > 0);
        // a second dash is still recharging
        float x1 = l.x;
        use(w, l, spacerts.units.Ability.DASH);
        run(w, 0.4f);
        assertEquals(x1, l.x, 60, "no second dash during the cooldown");
    }

    @Test
    void broodSwarmReleasesFiveBroodlingsWhenAHiveDies() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit h = spawn(w, ShipClass.HIVE, p, ms.x + 400, ms.y + 400);
        h.kill(false);
        run(w, 0.2f);
        assertEquals(0, w.count(p, ShipClass.BROODLING), "nothing without the research");

        p.techs.add(spacerts.research.Tech.BROOD_SWARM);
        Unit h2 = spawn(w, ShipClass.HIVE, p, ms.x + 400, ms.y + 400);
        h2.kill(false);
        run(w, 0.2f);
        assertEquals(World.BROODLINGS, w.count(p, ShipClass.BROODLING));
        assertEquals(spacerts.research.Tech.CARRIER_TECH, spacerts.research.Tech.BROOD_SWARM.prereq(), "a Hive lab upgrade");
    }

    // ------------------------------------------------------------------ destroy, surrender, chat

    private static int wrecks(World w) {
        int n = 0;
        for (spacerts.world.Body b : w.bodies) if (!b.dead && b.isWreck()) n++;
        return n;
    }

    @Test
    void destroyRefundsAQuarterAndLeavesNoWreck() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit b = spawn(w, ShipClass.BULWARK, p, ms.x + 300, ms.y);
        double e0 = p.energy = 1000, m0 = p.metal = 1000;
        int wr = wrecks(w);
        CommandExecutor.apply(w, new Command(Command.Type.DESTROY, p.id).unit(b));
        run(w, 0.1f);
        assertTrue(b.dead);
        assertEquals(e0 + ShipClass.BULWARK.s.energy / 4, p.energy, 0.5, "25% of the energy back");
        assertEquals(m0 + ShipClass.BULWARK.s.metal / 4, p.metal, 0.5, "25% of the metal back");
        assertEquals(wr, wrecks(w), "scuttled ships leave no wreck");
    }

    @Test
    void destroyingAStationRefundsItsQueueInFull() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        Unit st = spawn(w, ShipClass.FACTORY, p, ms.x + 400, ms.y);
        p.energy = p.metal = 5000;
        assertNull(w.production.enqueue(st, ShipClass.WISP));
        assertNull(w.production.enqueue(st, ShipClass.WISP));
        double e1 = p.energy, m1 = p.metal;
        double paidE = 5000 - e1, paidM = 5000 - m1; // what the two Wisps really cost (log pricing included)
        CommandExecutor.apply(w, new Command(Command.Type.DESTROY, p.id).unit(st));
        run(w, 0.1f);
        assertTrue(st.dead);
        assertEquals(e1 + paidE + ShipClass.FACTORY.s.energy / 4, p.energy, 15);
        assertEquals(m1 + paidM + ShipClass.FACTORY.s.metal / 4, p.metal, 15);
    }

    @Test
    void coloniesAndTheMothershipCantBeScuttled() {
        World w = make();
        Faction p = w.player;
        Unit ms = p.mothership;
        Unit colony = null;
        for (Unit u : w.units) if (u.owner == p && u.cls == ShipClass.COLONY) colony = u;
        Command c = new Command(Command.Type.DESTROY, p.id).unit(ms);
        CommandExecutor.apply(w, c);
        if (colony != null) CommandExecutor.apply(w, new Command(Command.Type.DESTROY, p.id).unit(colony));
        run(w, 0.1f);
        assertFalse(ms.dead, "the Mothership needs Surrender");
        if (colony != null) assertFalse(colony.dead, "colonies can't be scuttled");
        assertFalse(CommandExecutor.destroyable(ms));
    }

    @Test
    void surrenderEndsTheGame() {
        World w = make();
        Faction p = w.player;
        CommandExecutor.apply(w, new Command(Command.Type.SURRENDER, p.id));
        run(w, 0.2f);
        assertTrue(p.eliminated);
        assertTrue(w.gameOver);
    }

    private static Command chat(Faction f, String text, boolean team) {
        Command c = new Command(Command.Type.CHAT, f.id).arg(team ? 1 : 0);
        c.name = text;
        return c;
    }

    private static String lastChat(World w) {
        for (int i = w.messages.size() - 1; i >= 0; i--) if (w.messages.get(i).chat) return w.messages.get(i).text;
        return null;
    }

    @Test
    void chatReachesEveryoneAndTeamChatOnlyAllies() {
        World w = make();
        Faction p = w.player, r = rival(w);
        CommandExecutor.apply(w, chat(p, "  hello there  ", false));
        assertEquals("[All] " + p.name + ": hello there", lastChat(w));

        CommandExecutor.apply(w, chat(r, "secret plan", true));
        assertEquals("[All] " + p.name + ": hello there", lastChat(w), "an enemy's team chat isn't shown");
        r.team = p.team = 1;
        CommandExecutor.apply(w, chat(r, "secret plan", true));
        assertEquals("[Team] " + r.name + ": secret plan", lastChat(w), "an ally's team chat is");

        CommandExecutor.apply(w, chat(p, "x".repeat(300) + "é", false));
        assertEquals(("[All] " + p.name + ": ").length() + CommandExecutor.CHAT_MAX, lastChat(w).length(), "capped");
        int n = w.messages.size();
        CommandExecutor.apply(w, chat(p, "   ", false));
        assertEquals(n, w.messages.size(), "empty lines are dropped");
        assertTrue(w.messages.get(w.messages.size() - 1).life() > 12f, "chat stays up longer");
    }

    // ------------------------------------------------------------------ upkeep

    @Test
    void upkeepTiers() {
        Faction f = new Faction(9, "Test", com.badlogic.gdx.graphics.Color.WHITE, Faction.Kind.PLAYER);
        f.upkeepPoints = 30;
        assertEquals(0, EconomySystem.upkeepTier(f));
        assertEquals(0, EconomySystem.upkeep(f), 1e-6);
        f.upkeepPoints = 60;
        assertEquals(1, EconomySystem.upkeepTier(f));
        assertEquals(1.8, EconomySystem.upkeep(f), 1e-4);
        f.upkeepPoints = 150;
        assertEquals(2, EconomySystem.upkeepTier(f));
        assertEquals(9.0, EconomySystem.upkeep(f), 1e-4);
    }

    @Test
    void bigFleetsDrainEnergyButNeverBelowZero() {
        World w = make();
        Faction p = w.player;
        strip(w, p);
        Unit ms = p.mothership;
        for (int i = 0; i < 25; i++) spawn(w, ShipClass.BULWARK, p, ms.x + 400 + (i % 5) * 60, ms.y + (i / 5) * 60);
        spawn(w, ShipClass.FACTORY, p, ms.x - 500, ms.y);
        run(w, 0.1f);
        assertEquals(25 * ShipClass.BULWARK.s.pop + EconomySystem.UPKEEP_PER_STATION, p.upkeepPoints, "ships by housing, stations count 4");
        assertEquals(2, EconomySystem.upkeepTier(p));
        p.energy = 500;
        run(w, 10);
        assertTrue(p.energy < 500 - EconomySystem.upkeep(p) * 10 * 0.7f, "drained: " + p.energy);
        p.energy = 3;
        run(w, 5);
        assertTrue(p.energy >= 0, "never negative");
        assertTrue(p.energy < 3);
    }
}

package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.input.GameInput;
import spacerts.net.Lobby;
import spacerts.net.Lockstep;
import spacerts.net.NetClient;
import spacerts.net.NetHost;
import spacerts.research.Tech;
import spacerts.save.SaveManager;
import spacerts.screens.GameScreen;
import spacerts.systems.ConstructionSystem;
import spacerts.systems.TacticsSystem;
import spacerts.systems.UnitBehavior;
import spacerts.units.Ability;
import spacerts.units.ShipClass;
import spacerts.units.Tactic;
import spacerts.units.Unit;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Mothership Warp Jump and tier II unlocks, formation speed, Ctrl+A, research panel order, LAN joining and loading. */
class FeaturesTest {

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

    private static void clearField(World w) {
        for (Unit u : w.units) if (u.owner.kind == Faction.Kind.PIRATES || u.owner.kind == Faction.Kind.NATIVES) u.kill(true);
        run(w, 0.1f);
    }

    // ------------------------------------------------------------------ Mothership

    @Test
    void mothershipHasDoubleHull() {
        assertEquals(12000, ShipClass.MOTHERSHIP.s.hp, 0.01);
        assertTrue(ShipClass.MOTHERSHIP.s.has(Ability.WARP));
    }

    @Test
    void warpJumpNeedsTierTwoJumpsUpTo1000AndSprints() {
        World w = make();
        clearField(w);
        Unit ms = w.player.mothership;
        float c = Galaxy.CLASSIC_SIZE / 2;
        float dx = c - ms.x, dy = c - ms.y, d = (float) Math.hypot(dx, dy);
        float ux = dx / d, uy = dy / d; // toward the map centre, so the jump never hits the edge

        assertNotNull(w.behavior.useAbility(ms, Ability.WARP, ms.x + ux * 600, ms.y + uy * 600), "tier I can't jump");
        ms.tier = 2;
        float x0 = ms.x, y0 = ms.y;
        assertNull(w.behavior.useAbility(ms, Ability.WARP, ms.x + ux * 600, ms.y + uy * 600));
        assertEquals(600, Math.hypot(ms.x - x0, ms.y - y0), 1, "landed on the spot");
        assertEquals(UnitBehavior.WARP_CD, ms.abilityCds[Ability.WARP.ordinal()], 0.01, "20 minute cooldown");
        assertEquals(20 * 60f, UnitBehavior.WARP_CD, 0.01);
        assertNotNull(w.behavior.useAbility(ms, Ability.WARP, ms.x + ux * 300, ms.y + uy * 300), "recharging");

        ms.abilityCds[Ability.WARP.ordinal()] = 0;
        float x1 = ms.x, y1 = ms.y;
        assertNull(w.behavior.useAbility(ms, Ability.WARP, ms.x + ux * 3000, ms.y + uy * 3000));
        assertEquals(UnitBehavior.WARP_REACH, Math.hypot(ms.x - x1, ms.y - y1), 1, "far targets are clamped to 1000");

        // the sprint: scout speed for 20 s
        assertEquals(UnitBehavior.WARP_SPRINT, ms.sprintTimer, 0.01);
        ms.command(spacerts.units.Order.MOVE, ms.x + ux * 3000, ms.y + uy * 3000);
        float sx = ms.x, sy = ms.y;
        run(w, 3);
        float moved = (float) Math.hypot(ms.x - sx, ms.y - sy);
        assertTrue(moved > ShipClass.MOTHERSHIP.s.speed * 3 * 3, "much faster than a normal Mothership: " + moved);
        World back = SaveManager.fromJson(SaveManager.toJson(w));
        assertTrue(back.player.mothership.sprintTimer > 0, "sprint saved");
        run(w, UnitBehavior.WARP_SPRINT);
        assertTrue(ms.sprintTimer <= 0, "the sprint wears off");
    }

    // ------------------------------------------------------------------ tier II: stations and labs

    @Test
    void tierTwoAllowsFourStationsAndTwoLabs() {
        World w = make();
        Faction p = w.player;
        Unit ms = p.mothership;
        spawn(w, ShipClass.FACTORY, p, ms.x + 400, ms.y);
        spawn(w, ShipClass.FACTORY, p, ms.x - 400, ms.y);
        assertNotNull(w.construction.baseBlocker(p, ShipClass.FACTORY), "2 at tier I");
        spawn(w, ShipClass.HUB, p, ms.x, ms.y + 400);
        assertNotNull(w.construction.baseBlocker(p, ShipClass.HUB), "1 lab at tier I");

        ms.tier = 2;
        assertEquals(4, ConstructionSystem.maxStations(p));
        assertNull(w.construction.baseBlocker(p, ShipClass.FACTORY), "a 3rd at tier II");
        assertNull(w.construction.baseBlocker(p, ShipClass.HUB), "a 2nd lab at tier II");
        spawn(w, ShipClass.FACTORY, p, ms.x + 400, ms.y + 400);
        spawn(w, ShipClass.FACTORY, p, ms.x - 400, ms.y + 400);
        assertNotNull(w.construction.baseBlocker(p, ShipClass.FACTORY), "no 5th");
        Unit hub2 = spawn(w, ShipClass.HUB, p, ms.x, ms.y - 400);
        assertSame(hub2, p.hub2, "the second lab");
        assertNotNull(w.construction.baseBlocker(p, ShipClass.HUB), "no 3rd lab");
    }

    @Test
    void twoLabsResearchInParallel() {
        World w = make();
        Faction p = w.player;
        Unit ms = p.mothership;
        ms.tier = 2;
        spawn(w, ShipClass.HUB, p, ms.x, ms.y + 400);
        Unit hub2 = spawn(w, ShipClass.HUB, p, ms.x, ms.y - 400);
        p.energy = p.metal = 100000;
        java.util.Arrays.fill(p.rare, 50); // some techs need rare items
        assertNull(w.production.startResearch(p, Tech.HARVEST_1));
        assertNull(w.production.startResearch(p, Tech.CARGO), "the second lab takes it");
        assertSame(Tech.HARVEST_1, p.researching);
        assertSame(Tech.CARGO, p.researching2);
        assertNotNull(w.production.researchBlocker(p, Tech.DEEP_MINING), "both labs busy");
        assertNotNull(w.production.researchBlocker(p, Tech.CARGO), "already researching");

        World back = SaveManager.fromJson(SaveManager.toJson(w));
        assertSame(Tech.CARGO, back.player.researching2, "lab 2 saved");

        run(w, Math.max(Tech.HARVEST_1.time, Tech.CARGO.time) + 2);
        assertTrue(p.has(Tech.HARVEST_1) && p.has(Tech.CARGO), "both finished");

        // losing lab 2 loses only its research
        assertNull(w.production.startResearch(p, Tech.DEEP_MINING));
        assertNull(w.production.startResearch(p, Tech.SURVEY_DRILLS));
        Tech lab1 = p.researching;
        hub2.kill(false);
        run(w, 0.1f);
        assertNull(p.researching2, "lab 2's research is lost");
        assertSame(lab1, p.researching, "lab 1 keeps going");
    }

    // ------------------------------------------------------------------ formations

    @Test
    void formedUpShipsMoveAtLancerSpeed() {
        World w = make();
        clearField(w);
        Faction p = w.player;
        float x = Galaxy.CLASSIC_SIZE / 2, y = Galaxy.CLASSIC_SIZE * 0.3f;
        List<Unit> ships = new ArrayList<>();
        for (int i = 0; i < 2; i++) ships.add(spawn(w, ShipClass.BULWARK, p, x + i * 60, y));
        for (int i = 0; i < 6; i++) ships.add(spawn(w, ShipClass.LANCER, p, x - 100 + i * 30, y + 120));
        Unit hive = spawn(w, ShipClass.HIVE, p, x, y - 200); // the slowest ship: 62 on its own
        ships.add(hive);
        for (Unit u : ships) u.retreatOff = true;
        TacticsSystem.Formation fm = w.tactics.start(p, Tactic.BULLDOZER, ships, x, y + 6000);
        Unit bulwark = ships.get(0);
        assertEquals(-1f, w.tactics.formationSpeed(bulwark), "own speed while forming");
        for (int i = 0; i < 600 && fm.state == TacticsSystem.State.FORMING; i++) w.update(Lockstep.STEP);
        assertEquals(TacticsSystem.State.MARCHING, fm.state);
        float lancer = TacticsSystem.lancerSpeed(p);
        for (Unit u : ships) assertEquals(lancer, w.tactics.formationSpeed(u), 0.01, u.s.name + " marches at Lancer speed");
        float ay0 = fm.ay;
        run(w, 3);
        assertTrue(fm.ay - ay0 > ShipClass.HIVE.s.speed * 3 * 1.5f, "the block moves much faster than its slowest ship: " + (fm.ay - ay0));
        assertTrue(hive.dist(fm.slotX(memberOf(fm, hive)), fm.slotY(memberOf(fm, hive))) < 250, "and the Hive keeps up");
    }

    private static TacticsSystem.Member memberOf(TacticsSystem.Formation fm, Unit u) {
        for (TacticsSystem.Member m : fm.members) if (m.u == u) return m;
        throw new IllegalStateException();
    }

    // ------------------------------------------------------------------ Ctrl+A

    @Test
    void ctrlASkipsPatrolShips() {
        World w = make();
        Faction p = w.player;
        Unit patrol = spawn(w, ShipClass.PATROL, p, 100, 100);
        Unit skiff = spawn(w, ShipClass.PATROL_SKIFF, p, 120, 100);
        Unit lancer = spawn(w, ShipClass.LANCER, p, 140, 100);
        Unit bulwark = spawn(w, ShipClass.BULWARK, p, 160, 100);
        assertFalse(GameInput.isArmy(patrol));
        assertFalse(GameInput.isArmy(skiff));
        assertTrue(GameInput.isArmy(lancer));
        assertTrue(GameInput.isArmy(bulwark));
    }

    // ------------------------------------------------------------------ research panel order

    @Test
    void researchPanelGroupsBranchesAndFollowUps() {
        int total = 0;
        java.util.Set<Tech> seen = new java.util.HashSet<>();
        for (Tech.Branch b : Tech.PANEL_BRANCHES) {
            List<Tech> list = Tech.branchOrder(b);
            for (Tech t : list) {
                assertEquals(b, t.branch);
                assertTrue(seen.add(t), "listed once: " + t);
                Tech pre = t.prereq();
                if (pre != null && pre.branch == b) assertTrue(list.indexOf(pre) < list.indexOf(t), t + " comes after " + pre);
            }
            total += list.size();
        }
        assertEquals(Tech.values().length, total, "every tech is in the panel");
        List<Tech> mil = Tech.branchOrder(Tech.Branch.MILITARY);
        assertEquals(mil.indexOf(Tech.WEAPONS_1) + 1, mil.indexOf(Tech.WEAPONS_2), "Plasma Weapons II right under I");
        assertEquals(Tech.HUB_TIER_2, Tech.branchOrder(Tech.Branch.HUB).get(0));
    }

    // ------------------------------------------------------------------ LAN

    @Test
    void doubleClickingJoinTakesOnlyOneSeat() throws Exception {
        Lobby lobby = Lobby.create("Host", 4242);
        lobby.entries[1].seat = Lobby.Seat.OPEN;
        lobby.entries[1].name = "";
        lobby.entries[2].seat = Lobby.Seat.OPEN;
        lobby.entries[2].name = "";
        NetHost host = new NetHost(lobby);
        NetClient a = new NetClient("127.0.0.1", "Guest");
        a.close(); // the first click, abandoned by the second while still connecting
        NetClient b = new NetClient("127.0.0.1", "Guest");
        try {
            long until = System.currentTimeMillis() + 3000;
            while (b.seat < 0 && System.currentTimeMillis() < until) {
                host.pollLobby();
                b.pollLobby();
                Thread.sleep(10);
            }
            assertTrue(b.seat > 0, "the second attempt got a seat");
            until = System.currentTimeMillis() + 800; // give a stray HELLO time to arrive
            while (System.currentTimeMillis() < until) {
                host.pollLobby();
                Thread.sleep(10);
            }
            assertEquals(1, lobby.remoteHumans(), "seated once, not twice");
            assertTrue(a.isClosed());
        } finally {
            a.close();
            b.close();
            host.close();
        }
    }

    @Test
    void loadingTimeoutDropsPlayersWhoNeverFinish() throws Exception {
        Lobby lobby = Lobby.create("Host", 4242);
        lobby.entries[1].seat = Lobby.Seat.OPEN;
        lobby.entries[1].name = "";
        NetHost host = new NetHost(lobby);
        NetClient client = new NetClient("127.0.0.1", "Slow");
        float oldTimeout = Lockstep.READY_TIMEOUT;
        try {
            long until = System.currentTimeMillis() + 3000;
            while (client.seat < 0 && System.currentTimeMillis() < until) {
                host.pollLobby();
                client.pollLobby();
                Thread.sleep(10);
            }
            GameSetup hs = host.start();
            World hw = new World(hs);
            GalaxyGenerator.generate(hw);
            hw.flushPending();
            Lockstep hl = Lockstep.host(host);
            Lockstep.Runner sim = new Lockstep.Runner() {
                public void runTurn(List<spacerts.net.Command> cmds) {
                    for (spacerts.net.Command c : cmds) spacerts.net.CommandExecutor.apply(hw, c);
                    for (int i = 0; i < Lockstep.TURN_TICKS; i++) hw.update(Lockstep.STEP);
                }

                public World world() {
                    return hw;
                }
            };
            Lockstep.READY_TIMEOUT = 0.5f;
            hl.markReady(); // the client never reports ready
            for (int i = 0; i < 4; i++) hl.update(0.1f, sim);
            assertFalse(hl.go, "waits for the slow player first");
            for (int i = 0; i < 20 && !hl.go; i++) hl.update(0.1f, sim);
            assertTrue(hl.go, "starts after the timeout");
            assertEquals(0, hl.connectedClients(), "the player still loading was dropped");
            for (int i = 0; i < 20; i++) {
                hl.update(Lockstep.TURN_TIME, sim);
                Thread.sleep(2);
            }
            assertEquals(Faction.Kind.RIVAL, hw.factions.get(1).kind, "a bot took over their empire");
        } finally {
            Lockstep.READY_TIMEOUT = oldTimeout;
            client.close();
            host.close();
        }
    }

    @Test
    void warmUpLeavesTheRealWorldAlone() {
        World w = make();
        long before = Lockstep.checksum(w);
        GameScreen.warmUp(w.setup);
        assertEquals(before, Lockstep.checksum(w), "the throwaway copy doesn't touch the real game");
    }
}

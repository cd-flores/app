package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.ai.RivalAI;
import spacerts.net.Command;
import spacerts.net.CommandExecutor;
import spacerts.net.Lobby;
import spacerts.net.Lockstep;
import spacerts.net.NetClient;
import spacerts.net.NetHost;
import spacerts.save.SaveManager;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bots, teams, the command layer and LAN lockstep. */
class MultiplayerTest {

    private static World make(GameSetup s) {
        World w = new World(s);
        GalaxyGenerator.generate(w);
        return w;
    }

    private static void run(World w, float seconds) {
        int n = (int) (seconds / Lockstep.STEP);
        for (int i = 0; i < n; i++) w.update(Lockstep.STEP);
    }

    private static GameSetup threeWay(int playerTeam, int bot1Team, int bot2Team) {
        GameSetup g = new GameSetup();
        g.seed = 77;
        GameSetup.Slot p = new GameSetup.Slot(true, "Alice", 0);
        p.team = playerTeam;
        GameSetup.Slot a = new GameSetup.Slot(false, "Bot A", 1);
        a.team = bot1Team;
        GameSetup.Slot b = new GameSetup.Slot(false, "Bot B", 2);
        b.team = bot2Team;
        b.botDifficulty = Difficulty.INSANE;
        g.slots.add(p);
        g.slots.add(a);
        g.slots.add(b);
        return g;
    }

    // ------------------------------------------------------------------ bots

    @Test
    void botsSitStillForThreeMinutes() {
        World w = make(GameSetup.skirmish(31, 2, Difficulty.HARD));
        run(w, RivalAI.START_DELAY - 2);
        for (int i = 1; i <= 2; i++) {
            Faction r = w.factions.get(i);
            assertNull(r.researching, "no research before 3:00");
            for (Unit u : w.units) {
                if (u.dead || u.owner != r) continue;
                if (!u.s.stationary) assertEquals(Order.IDLE, u.order, r.name + " " + u.s.name + " should not move before 3:00");
                if (u.queue != null) assertTrue(u.queue.items.isEmpty(), "nothing queued before 3:00");
            }
        }
        run(w, 25);
        Faction r = w.factions.get(1);
        boolean acted = false;
        for (Unit u : w.units) if (!u.dead && u.owner == r && (u.order != Order.IDLE || (u.queue != null && !u.queue.items.isEmpty()))) acted = true;
        assertTrue(acted, "the bot gets going after 3:00");
        // the attack grace period is unchanged
        assertEquals(12 * 60, r.difficulty.firstStrike);
    }

    @Test
    void shipCapLimitsEasyBotsButNotInsane() {
        assertEquals(64, Difficulty.EASY.shipCap);
        assertEquals(-1, Difficulty.INSANE.shipCap);
        for (Difficulty d : new Difficulty[]{Difficulty.EASY, Difficulty.INSANE}) {
            World w = make(GameSetup.skirmish(32, 1, d));
            Faction r = w.factions.get(1);
            Unit ms = r.mothership;
            w.spawn(ShipClass.FACTORY, r, ms.x + 300, ms.y);
            for (int i = 0; i < 64 - 3; i++) w.spawn(ShipClass.LANCER, r, ms.x - 200 + (i % 8) * 30, ms.y + 200 + (i / 8) * 30);
            // plenty of housing, so only the cap can stop the bot
            int colonies = 0;
            for (spacerts.world.Body b : new ArrayList<>(w.bodies))
                if (colonies < 12 && b.colonizable()) {
                    w.colonize(b, r);
                    colonies++;
                }
            w.flushPending();
            w.computePopulation();
            assertTrue(r.popCap - r.popUsed > 40, "spare housing: " + r.popUsed + "/" + r.popCap);
            w.time = 1200;
            r.energy = r.metal = 5_000_000;
            int before = r.ai.warships();
            assertTrue(before >= 64);
            assertEquals(d == Difficulty.EASY, r.ai.atShipCap());
            run(w, 30);
            r.energy = r.metal = 5_000_000;
            run(w, 30);
            if (d == Difficulty.EASY) assertTrue(r.ai.warships() <= before, "Easy builds nothing past its cap, had " + r.ai.warships());
            else assertTrue(r.ai.warships() > before, "Insane keeps building past 64");
        }
    }

    // ------------------------------------------------------------------ teams

    @Test
    void alliesHoldFireEnemiesDont() {
        World w = make(threeWay(1, 1, 2));
        Faction me = w.player, ally = w.factions.get(1), enemy = w.factions.get(2);
        assertFalse(me.hostileTo(ally));
        assertTrue(me.hostileTo(enemy));
        assertTrue(me.hostileTo(w.pirates));
        Unit ms = me.mothership;
        Unit friend = w.spawn(ShipClass.LANCER, ally, ms.x + 120, ms.y + 120);
        Unit foe = w.spawn(ShipClass.LANCER, enemy, ms.x - 120, ms.y - 120);
        w.flushPending();
        run(w, 6);
        assertTrue(!friend.dead && friend.hp == friend.maxHp(), "an ally next to our mothership is left alone");
        assertTrue(foe.dead || foe.hp < foe.maxHp(), "an enemy next to our mothership is shot");
        // allies share vision
        w.updateVision();
        assertTrue(me.vision.isVisible(ally.mothership.x, ally.mothership.y), "allied territory is visible");
    }

    @Test
    void teamVictoryAndSaveRoundTrip() {
        World w = make(threeWay(1, 1, 2));
        w.eliminate(w.factions.get(2));
        assertTrue(w.victoryAnnounced, "the last enemy falling wins it for the team");
        assertTrue(w.matchOver, "and ends the match");
        assertNotNull(w.winner);
        assertEquals(1, w.winner.team, "the team won");
        float t = w.time;
        w.update(spacerts.net.Lockstep.STEP);
        assertEquals(t, w.time, 1e-6, "frozen on every machine");
        World back = SaveManager.fromJson(SaveManager.toJson(w));
        assertEquals(3, back.rivalCount + 1);
        assertEquals(1, back.factions.get(1).team);
        assertEquals(Difficulty.INSANE, back.factions.get(2).difficulty);
        assertEquals("Alice", back.player.name);
    }

    // ------------------------------------------------------------------ commands

    @Test
    void commandsOnlyMoveTheIssuersUnits() {
        World w = make(threeWay(0, 0, 0));
        List<Unit> theirs = new ArrayList<>();
        for (Unit u : w.units) if (u.owner == w.factions.get(1) && u.cls == ShipClass.LANCER) theirs.add(u);
        Command c = new Command(Command.Type.ORDER, w.player.id).units(theirs).at(5000, 5000);
        CommandExecutor.apply(w, c);
        for (Unit u : theirs) assertEquals(Order.IDLE, u.order, "someone else's ships ignore our orders");

        List<Unit> mine = new ArrayList<>();
        for (Unit u : w.units) if (u.owner == w.player && u.cls == ShipClass.LANCER) mine.add(u);
        CommandExecutor.apply(w, new Command(Command.Type.ORDER, w.player.id).units(mine).at(5000, 5000));
        for (Unit u : mine) assertEquals(Order.MOVE, u.order);
    }

    /** Two machines with the same setup and the same commands at the same ticks end in the same state. */
    @Test
    void simulationIsDeterministic() {
        GameSetup s = threeWay(0, 0, 0);
        World a = make(s.copy()), b = make(s.copy());
        int ticks = (int) ((RivalAI.START_DELAY + 40) / Lockstep.STEP);
        for (int i = 0; i < ticks; i++) {
            if (i == 30 || i == 2000) {
                for (World w : new World[]{a, b}) {
                    List<Unit> mine = new ArrayList<>();
                    for (Unit u : w.units) if (u.owner == w.player && u.cls == ShipClass.LANCER) mine.add(u);
                    CommandExecutor.apply(w, new Command(Command.Type.ORDER, w.player.id).units(mine).at(6000 + i, 6000).arg(1));
                    CommandExecutor.apply(w, new Command(Command.Type.ENQUEUE, w.player.id).target(w.player.mothership).cls(ShipClass.HARVESTER));
                    if (i == 30) {
                        w.spawn(ShipClass.FACTORY, w.player, w.player.mothership.x + 300, w.player.mothership.y);
                        CommandExecutor.apply(w, new Command(Command.Type.RALLY_STATIONS, w.player.id).arg(1));
                    }
                }
            }
            a.update(Lockstep.STEP);
            b.update(Lockstep.STEP);
        }
        assertEquals(Lockstep.checksum(a), Lockstep.checksum(b));
    }

    // ------------------------------------------------------------------ LAN

    /** Minimal game-screen stand-in for the lockstep. */
    private static final class Sim implements Lockstep.Runner {
        final World w;

        Sim(World w) {
            this.w = w;
        }

        @Override
        public void runTurn(List<Command> commands) {
            for (Command c : commands) CommandExecutor.apply(w, c);
            for (int i = 0; i < Lockstep.TURN_TICKS; i++) w.update(Lockstep.STEP);
        }

        @Override
        public World world() {
            return w;
        }
    }

    @Test
    void lanLobbyAndLockstepOverLocalhost() throws Exception {
        Lobby lobby = Lobby.create("Host", 4242);
        lobby.entries[1].seat = Lobby.Seat.OPEN; // seat 1 for the client
        lobby.entries[1].name = "";
        lobby.entries[2].seat = Lobby.Seat.BOT;
        lobby.entries[2].name = "Bot";
        NetHost host = new NetHost(lobby);
        NetClient client = new NetClient("127.0.0.1", "Guest");
        try {
            long until = System.currentTimeMillis() + 5000;
            while ((client.lobby == null || client.seat < 0) && System.currentTimeMillis() < until) {
                host.pollLobby();
                client.pollLobby();
                Thread.sleep(10);
            }
            assertEquals(1, client.seat, "the client takes the open seat");
            assertEquals("Guest", client.lobby.entries[1].name);

            client.sendPrefs(2, 4);
            until = System.currentTimeMillis() + 3000;
            while (lobby.entries[1].team != 2 && System.currentTimeMillis() < until) {
                host.pollLobby();
                Thread.sleep(10);
            }
            assertEquals(2, lobby.entries[1].team, "clients pick their own team");

            GameSetup hs = host.start();
            until = System.currentTimeMillis() + 3000;
            while (client.started == null && System.currentTimeMillis() < until) {
                client.pollLobby();
                Thread.sleep(10);
            }
            GameSetup cs = client.started;
            assertNotNull(cs);
            assertEquals(0, hs.localSlot);
            assertEquals(1, cs.localSlot);
            World hw = make(hs), cw = make(cs);
            assertEquals("Host", hw.player.name);
            assertEquals("Guest", cw.player.name);

            Lockstep hl = Lockstep.host(host), cl = Lockstep.client(client);
            Sim hsim = new Sim(hw), csim = new Sim(cw);
            for (int frame = 0; frame < 300; frame++) {
                if (frame == 20) { // each side orders its own lancers
                    List<Unit> mine = new ArrayList<>();
                    for (Unit u : cw.units) if (u.owner == cw.player && u.cls == ShipClass.LANCER) mine.add(u);
                    Command c = new Command(Command.Type.ORDER, cw.player.id).units(mine).at(7000, 7000);
                    cl.submit(c);
                    List<Unit> hm = new ArrayList<>();
                    for (Unit u : hw.units) if (u.owner == hw.player && u.cls == ShipClass.LANCER) hm.add(u);
                    hl.submit(new Command(Command.Type.ORDER, hw.player.id).units(hm).at(3000, 3000));
                }
                hl.update(Lockstep.TURN_TIME, hsim);
                cl.update(Lockstep.TURN_TIME, csim);
                Thread.sleep(3);
            }
            // let the client catch up to the host, then compare
            until = System.currentTimeMillis() + 5000;
            while (cl.turn < hl.turn && System.currentTimeMillis() < until) {
                cl.update(0, csim);
                Thread.sleep(5);
            }
            assertEquals(hl.turn, cl.turn, "the client ran every turn the host ran");
            assertEquals(Lockstep.checksum(hw), Lockstep.checksum(cw), "both machines agree");
            assertEquals(-1, hl.desyncTurn);
            boolean guestMoved = false;
            for (Unit u : hw.units) if (u.owner == hw.factions.get(1) && u.cls == ShipClass.LANCER && u.order != Order.IDLE) guestMoved = true;
            assertTrue(guestMoved, "the guest's order reached the host's simulation");

            // the guest leaves: a bot takes over on the host
            client.close();
            until = System.currentTimeMillis() + 3000;
            while (hw.factions.get(1).kind == Faction.Kind.PLAYER && System.currentTimeMillis() < until) {
                hl.update(Lockstep.TURN_TIME, hsim);
                Thread.sleep(5);
            }
            assertEquals(Faction.Kind.RIVAL, hw.factions.get(1).kind, "a bot took over the guest's empire");
        } finally {
            client.close();
            host.close();
        }
    }
}

package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.net.Command;
import spacerts.net.CommandExecutor;
import spacerts.net.Lockstep;
import spacerts.research.RareItem;
import spacerts.research.Tech;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Cheats;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.Message;
import spacerts.world.World;

import static org.junit.jupiter.api.Assertions.*;

/** The Warcraft III cheat codes: activation, the list, and what each one does. */
class CheatTest {

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

    private static void cheat(World w, String line) {
        Command c = new Command(Command.Type.CHEAT, w.player.id);
        c.name = line;
        CommandExecutor.apply(w, c);
    }

    private static World unlocked() {
        World w = make();
        cheat(w, "ThereIsNoCowLevel");
        assertTrue(w.player.cheatsUnlocked);
        return w;
    }

    private static Faction rival(World w) {
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) return f;
        throw new IllegalStateException();
    }

    private static Unit spawn(World w, ShipClass c, Faction f, float x, float y) {
        Unit u = w.spawn(c, f, x, y);
        w.flushPending();
        return u;
    }

    @Test
    void lockedUntilTheActivationCode() {
        World w = make();
        Faction p = w.player;
        double e0 = p.energy;
        cheat(w, "greedisgood 1000");
        assertEquals(e0, p.energy, 0.01, "locked: nothing happens");
        assertFalse(p.cheatsUsed);

        cheat(w, "There Is No Cow Level");
        assertTrue(p.cheatsUnlocked, "case and spaces don't matter");
        cheat(w, "GreedIsGood 1000");
        assertEquals(e0 + 1000, p.energy, 0.01);
        assertTrue(p.cheatsUsed);

        cheat(w, "whosyourdaddy");
        assertTrue(p.godMode);
        cheat(w, "thereisnocowlevel");
        assertFalse(p.cheatsUnlocked, "typed again: locked");
        assertFalse(p.godMode, "and every toggle is off");
    }

    @Test
    void resourceCheats() {
        World w = unlocked();
        Faction p = w.player;
        double e0 = p.energy, m0 = p.metal;
        int r0 = p.rare[RareItem.values()[0].ordinal()];
        cheat(w, "keysersoze");
        cheat(w, "LeafItToMe");
        assertEquals(e0 + 500, p.energy, 0.01, "default 500 energy");
        assertEquals(m0 + 500, p.metal, 0.01, "default 500 metal");
        cheat(w, "greed is good 300");
        assertEquals(e0 + 800, p.energy, 0.01);
        assertEquals(m0 + 800, p.metal, 0.01);
        assertEquals(r0 + 3, p.rare[RareItem.values()[0].ordinal()], "300 / 100 of every rare item");
    }

    @Test
    void whosYourDaddyIsGodMode() {
        World w = unlocked();
        Faction p = w.player;
        Unit ms = p.mothership;
        Unit l = spawn(w, ShipClass.LANCER, p, ms.x + 300, ms.y);
        Unit b = spawn(w, ShipClass.BULWARK, rival(w), ms.x + 340, ms.y);
        cheat(w, "WhosYourDaddy");
        float hp = l.hp;
        w.combat.applyDamage(l, 5000, true, b);
        assertEquals(hp, l.hp, 0.01, "untouchable");
        w.combat.applyDamage(b, 1, false, l);
        assertTrue(b.dead, "one hit kills");
    }

    @Test
    void warpTenAndJohnGaltSpeedThingsUp() {
        World slow = unlocked(), fast = unlocked();
        cheat(fast, "warpten");
        float[] progress = new float[2];
        World[] ws = {slow, fast};
        for (int i = 0; i < 2; i++) {
            World w = ws[i];
            w.player.energy = w.player.metal = 5000;
            assertNull(w.production.enqueue(w.player.mothership, ShipClass.HARVESTER));
            run(w, 0.3f);
            progress[i] = w.player.mothership.queue.isEmpty() ? 1f : w.player.mothership.queue.progress;
        }
        assertTrue(progress[1] > progress[0] * 8, "about 10x: " + progress[1] + " vs " + progress[0]);
        cheat(fast, "WhoIsJohnGalt");
        assertTrue(fast.player.fastResearch);
    }

    @Test
    void pointBreakAndSynergyLiftLimits() {
        World w = unlocked();
        Faction p = w.player;
        Unit fac = spawn(w, ShipClass.FACTORY, p, p.mothership.x + 400, p.mothership.y);
        p.popUsed = p.popCap;
        assertNotNull(w.production.blocker(fac, ShipClass.LANCER), "housing full");
        cheat(w, "pointbreak");
        assertNull(w.production.blocker(fac, ShipClass.LANCER), "no housing limit");
        assertNotNull(w.production.blocker(fac, ShipClass.LEVIATHAN), "needs research and tier III");
        cheat(w, "synergy");
        assertNull(w.production.blocker(fac, ShipClass.LEVIATHAN), "no tech tree");
    }

    @Test
    void sharpAndShinyGrantsEverything() {
        World w = unlocked();
        Faction p = w.player;
        cheat(w, "SharpAndShiny");
        for (Tech t : Tech.values()) assertTrue(p.has(t), t + " researched");
        assertEquals(3, p.mothership.tier);
    }

    @Test
    void cooldownCheats() {
        World w = unlocked();
        Faction p = w.player;
        Unit wisp = spawn(w, ShipClass.WISP, p, p.mothership.x + 300, p.mothership.y);
        assertNull(w.behavior.useAbility(wisp, spacerts.units.Ability.PING));
        assertTrue(wisp.abilityCds[spacerts.units.Ability.PING.ordinal()] > 0);
        cheat(w, "TheDudeAbides");
        assertEquals(0, wisp.abilityCds[spacerts.units.Ability.PING.ordinal()], 1e-6, "reset");
        cheat(w, "thereisnospoon");
        assertNull(w.behavior.useAbility(wisp, spacerts.units.Ability.PING));
        assertNull(w.behavior.useAbility(wisp, spacerts.units.Ability.PING), "no cooldown at all");
    }

    @Test
    void defeatVictoryAndStrengthAndHonor() {
        World w = unlocked();
        cheat(w, "AllYourBaseAreBelongToUs");
        assertTrue(rival(w).eliminated, "instant victory");

        World h = unlocked();
        cheat(h, "strengthandhonor");
        h.player.mothership.kill(false);
        run(h, 0.2f);
        assertFalse(h.player.eliminated, "the empire fights on");
        assertFalse(h.gameOver);

        World b = unlocked();
        cheat(b, "SomebodySetUpUsTheBomb");
        assertTrue(b.player.eliminated);
        assertTrue(b.gameOver, "instant defeat");
    }

    @Test
    void timerFogAndMotherland() {
        World w = unlocked();
        Faction p = w.player;
        float next = w.threats.nextWave;
        cheat(w, "riseandshine");
        assertEquals(next + 300, w.threats.nextWave, 0.01);
        cheat(w, "daylightsavings 60");
        assertEquals(w.time + 60, w.threats.nextWave, 0.01);
        cheat(w, "DayLightSavings");
        assertTrue(w.raidsFrozen);
        float frozen = w.threats.nextWave - w.time;
        run(w, 5);
        assertEquals(frozen, w.threats.nextWave - w.time, 0.2, "the raid timer stands still");

        cheat(w, "iseedeadpeople");
        run(w, 0.5f);
        assertTrue(p.vision.isVisible(10, 10) && p.vision.isVisible(spacerts.world.Galaxy.CLASSIC_SIZE - 10, 10), "no fog");

        cheat(w, "motherland");
        float c = spacerts.world.Galaxy.CLASSIC_SIZE / 2;
        assertEquals(c, p.mothership.x, 1);
        assertEquals(c, p.mothership.y, 1);
    }

    @Test
    void cheatListPrintsLocallyAndShowsToggles() {
        World w = unlocked();
        cheat(w, "whosyourdaddy");
        int before = w.messages.size();
        Cheats.list(w, w.player);
        assertTrue(w.messages.size() > before + 20, "one line per code");
        boolean on = false;
        for (Message m : w.messages) if (m.chat && m.text.startsWith("[Cheats] WhosYourDaddy") && m.text.endsWith("(ON)")) on = true;
        assertTrue(on, "toggle state shown");
        assertTrue(Cheats.isListRequest("cheatlist") && Cheats.isListRequest("/cheats") && Cheats.isListRequest("/help cheats"));
        assertFalse(Cheats.isCheat("hello there"));
        assertFalse(Cheats.isCheat("warptenx"), "only exact codes");
        assertTrue(Cheats.isCheat("Warp Ten"));
    }

    @Test
    void noCheatsWithAnotherHuman() {
        GameSetup g = GameSetup.skirmish(91, 1, Difficulty.NORMAL);
        g.slots.get(1).human = true;
        World w = new World(g);
        GalaxyGenerator.generate(w);
        assertFalse(Cheats.allowed(w));
        cheat(w, "thereisnocowlevel");
        assertFalse(w.player.cheatsUnlocked, "ignored in a game with two humans");
    }
}

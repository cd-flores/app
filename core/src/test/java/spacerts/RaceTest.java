package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.save.SaveManager;
import spacerts.units.Race;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Race groundwork: build lists, flagship / builder roles, and the race carried by setups, the network form and saves. */
class RaceTest {

    private static World make() {
        World w = new World(GameSetup.skirmish(17, 2, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        return w;
    }

    @Test
    void originalRosterKeepsItsBuildLists() {
        Race r = Race.COALITION;
        assertArrayEquals(new ShipClass[]{ShipClass.HARVESTER, ShipClass.PATROL, ShipClass.DEFENDER, ShipClass.MS_TIER_2,
                ShipClass.MS_TIER_3, ShipClass.STELLAR_REFINEMENT}, r.buildList(ShipClass.MOTHERSHIP));
        assertEquals(16, r.buildList(ShipClass.FACTORY).length);
        assertEquals(12, r.buildList(ShipClass.HARVESTER).length);
        assertArrayEquals(new ShipClass[]{ShipClass.SURVEYOR}, r.buildList(ShipClass.HUB));
        assertEquals(9, r.buildList(ShipClass.COMMAND).length);
        assertEquals(0, r.buildList(ShipClass.LANCER).length, "a warship builds nothing");
        assertSame(r.buildList(ShipClass.FACTORY), ShipClass.FACTORY.buildList(r));
    }

    @Test
    void flagshipAndBuilderAreRoles() {
        for (ShipClass c : ShipClass.values()) {
            assertEquals(c == ShipClass.MOTHERSHIP, c.isFlagship(), c + " flagship");
            assertEquals(c == ShipClass.HARVESTER, c.isBuilder(), c + " builder");
        }
        for (Race r : Race.values()) {
            assertTrue(r.flagship.isFlagship(), r + " flagship");
            assertTrue(r.worker.isBuilder(), r + " worker");
            assertTrue(r.canBuild(r.flagship, r.worker), r + " flagship builds its worker");
            for (ShipClass c : r.botArmy) assertTrue(r.canBuild(ShipClass.FACTORY, c), r + " bot builds " + c);
        }
    }

    @Test
    void factionsStartWithTheirRacesFleet() {
        World w = make();
        for (Faction f : w.factions) {
            if (!f.isMajor()) continue;
            assertEquals(Race.COALITION, f.race);
            assertNotNull(f.mothership);
            assertSame(f.race.flagship, f.mothership.cls);
            int workers = 0;
            for (Unit u : w.units) if (u.owner == f && u.cls == f.race.worker) workers++;
            assertEquals(4, workers, f.name + " workers");
        }
    }

    @Test
    void producersOnlyBuildTheirRacesList() {
        World w = make();
        Unit ms = w.player.mothership;
        String why = w.production.blocker(ms, ShipClass.LANCER);
        assertNotNull(why);
        assertTrue(why.contains("can't build"), why);
        assertNull(w.production.blocker(ms, ShipClass.HARVESTER));
    }

    @Test
    void losingTheFlagshipLosesTheGame() {
        World w = make();
        Faction rival = null;
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) rival = f;
        assertNotNull(rival);
        rival.mothership.kill(false);
        w.update(1 / 30f);
        assertTrue(rival.eliminated);
    }

    @Test
    void raceTravelsInTheSetupAndSaves() throws Exception {
        GameSetup g = GameSetup.skirmish(5, 2, Difficulty.HARD);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        g.write(new DataOutputStream(bytes));
        GameSetup back = GameSetup.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        for (int i = 0; i < g.slots.size(); i++) assertEquals(g.slots.get(i).race, back.slots.get(i).race);
        assertEquals(g.slots.get(1).race, g.copy().slots.get(1).race);

        World w = make();
        String json = SaveManager.toJson(w);
        assertTrue(json.contains("COALITION"), "race saved");
        World l = SaveManager.fromJson(json);
        for (int i = 0; i < w.factions.size(); i++) assertEquals(w.factions.get(i).race, l.factions.get(i).race);

        assertEquals(Race.COALITION, Race.named(null), "older saves have no race");
        assertEquals(Race.COALITION, Race.named("NOPE"));
        assertEquals(Race.COALITION, Race.of(99));
        assertTrue(Arrays.asList(Race.values()).contains(Race.of(0)));
    }
}

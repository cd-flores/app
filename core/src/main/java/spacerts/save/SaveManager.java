package spacerts.save;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import spacerts.research.RareItem;
import spacerts.research.Tech;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.PlanetType;
import spacerts.world.StarSystem;
import spacerts.world.SystemArchetype;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JSON save/load of the full world state. */
public final class SaveManager {
    private SaveManager() {}

    public static final String FILE = "nebula_dominion_save.json";

    public static class SaveData {
        public long seed;
        public int rivals, wave, nextId, initialAsteroids;
        public float time, nextWave;
        public boolean victoryAnnounced;
        public String difficulty;
        public FactionData[] factions;
        public BodyData[] bodies;
        public UnitData[] units;
        public boolean[] explored;
        /** Hive galaxy: each system's name and archetype, by system id (geometry is rebuilt from the mode). */
        public String[] sysNames, sysArch;
        public float pirateRespawn;
        /** Hive hex radius the save was made with (0 in old saves = the original 3600 layout). */
        public float hexR;
        public boolean ascended;
    }

    public static class FactionData {
        public double energy, metal;
        public float researchProgress;
        public int[] rare;
        public String[] techs;
        public String researching;
        public boolean eliminated, hasFlag, autoDefenseOff;
        public boolean[] found;
        public int refinement;
        public float flagX, flagY;
    }

    public static class BodyData {
        public String kind, type, name;
        public float x, y, r, guardRadius, guardTimer, cr, cg, cb, life;
        public double metal, maxMetal;
        public int maxGuards, defenseLevel, turretPoints, shieldPoints; // defenseLevel: older saves
        public int link = -1, side = -1; // jump gates: index of the paired gate, hex side
        public int lootNeutron, lootExotic;
        public boolean giant;
    }

    public static class UnitData {
        public int id, owner, home = -1, body = -1, parent = -1, harvestState, buildBody = -1;
        public String buildCls;
        public float buildX, buildY;
        public String cls, order;
        public String[] queue;
        /** What was paid for each queued item: energy, metal, energy, metal, ... (older saves: missing = base price). */
        public int[] queuePaid;
        public float x, y, hp, shield, angle, tx, ty, anchorX, anchorY, cargo, work, progress, rallyX, rallyY, radius, abilityCd;
        public boolean cargoEnergy, hasRally, leaving;
        public boolean retreating, retreatOff, autoTask;
        public float homeX, homeY;
        public int station = -1; // pirate garrison: id of its stronghold
        public int tier = 1;
        public boolean hasPost;
        public float postX, postY;
    }

    public static FileHandle file() {
        return Gdx.files.local(FILE);
    }

    public static boolean exists() {
        return file().exists();
    }

    public static void save(World w) {
        file().writeString(toJson(w), false);
    }

    public static String toJson(World w) {
        SaveData d = new SaveData();
        d.seed = w.seed;
        d.rivals = w.rivalCount;
        d.time = w.time;
        d.nextWave = w.threats.nextWave;
        d.wave = w.threats.wave;
        d.nextId = w.nextId;
        d.initialAsteroids = w.initialAsteroids;
        d.victoryAnnounced = w.victoryAnnounced;
        d.difficulty = w.difficulty.name();
        d.explored = w.player.vision.explored.clone();
        d.pirateRespawn = w.pirateRespawn;
        d.hexR = w.galaxy.hive ? spacerts.world.Galaxy.HEX_R : 0;
        d.ascended = w.ascended;
        int ns = w.galaxy.systems.size();
        d.sysNames = new String[ns];
        d.sysArch = new String[ns];
        for (int i = 0; i < ns; i++) {
            StarSystem s = w.galaxy.systems.get(i);
            d.sysNames[i] = s.name;
            d.sysArch[i] = s.archetype.name();
        }

        d.factions = new FactionData[w.factions.size()];
        for (int i = 0; i < w.factions.size(); i++) {
            Faction f = w.factions.get(i);
            FactionData fd = new FactionData();
            fd.energy = f.energy;
            fd.metal = f.metal;
            fd.rare = f.rare.clone();
            fd.techs = new String[f.techs.size()];
            int k = 0;
            for (Tech t : f.techs) fd.techs[k++] = t.name();
            fd.researching = f.researching != null ? f.researching.name() : null;
            fd.researchProgress = f.researchProgress;
            fd.eliminated = f.eliminated;
            fd.hasFlag = f.hasFlag;
            fd.autoDefenseOff = !f.autoDefense;
            fd.flagX = f.flagX;
            fd.flagY = f.flagY;
            fd.found = f.found.clone();
            fd.refinement = f.refinement;
            d.factions[i] = fd;
        }

        List<Body> live = new ArrayList<>();
        for (Body b : w.bodies) if (!b.dead) live.add(b);
        Map<Body, Integer> bodyIndex = new HashMap<>();
        d.bodies = new BodyData[live.size()];
        for (int i = 0; i < live.size(); i++) {
            Body b = live.get(i);
            bodyIndex.put(b, i);
            BodyData bd = new BodyData();
            bd.kind = b.kind.name();
            bd.type = b.type != null ? b.type.name() : null;
            bd.name = b.name;
            bd.x = b.x;
            bd.y = b.y;
            bd.r = b.radius;
            bd.metal = b.metal;
            bd.maxMetal = b.maxMetal;
            bd.life = b.life;
            bd.guardRadius = b.guardRadius;
            bd.guardTimer = b.guardTimer;
            bd.maxGuards = b.maxGuards;
            bd.turretPoints = b.turretPoints;
            bd.shieldPoints = b.shieldPoints;
            bd.cr = b.color.r;
            bd.cg = b.color.g;
            bd.cb = b.color.b;
            bd.side = b.side;
            bd.lootNeutron = b.lootNeutron;
            bd.lootExotic = b.lootExotic;
            bd.giant = b.giant;
            d.bodies[i] = bd;
        }
        for (int i = 0; i < live.size(); i++) {
            Body b = live.get(i);
            Integer li = b.link != null ? bodyIndex.get(b.link) : null;
            if (li != null) d.bodies[i].link = li;
        }

        List<UnitData> uds = new ArrayList<>();
        for (Unit u : w.units) {
            if (u.dead) continue;
            UnitData ud = new UnitData();
            ud.id = u.id;
            ud.cls = u.cls.name();
            ud.owner = u.owner.id;
            ud.x = u.x;
            ud.y = u.y;
            ud.hp = u.hp;
            ud.shield = u.shield;
            ud.angle = u.angle;
            ud.radius = u.radius;
            ud.order = u.order.name();
            ud.tx = u.tx;
            ud.ty = u.ty;
            ud.anchorX = u.anchorX;
            ud.anchorY = u.anchorY;
            ud.cargo = u.cargo;
            ud.cargoEnergy = u.cargoEnergy;
            ud.harvestState = u.harvestState;
            ud.work = u.work;
            ud.abilityCd = u.abilityCd;
            ud.leaving = u.leaving;
            ud.retreating = u.retreating;
            ud.retreatOff = u.retreatOff;
            ud.autoTask = u.autoTask;
            ud.homeX = u.homeX;
            ud.homeY = u.homeY;
            ud.station = u.station != null && !u.station.dead ? u.station.id : -1;
            ud.tier = u.tier;
            ud.hasPost = u.hasPost;
            ud.postX = u.postX;
            ud.postY = u.postY;
            ud.hasRally = u.hasRally;
            ud.rallyX = u.rallyX;
            ud.rallyY = u.rallyY;
            Integer hi = u.home != null ? bodyIndex.get(u.home) : null;
            ud.home = hi != null ? hi : -1;
            Integer bi = u.body != null ? bodyIndex.get(u.body) : null;
            ud.body = bi != null ? bi : -1;
            if (u.buildCls != null) {
                ud.buildCls = u.buildCls.name();
                ud.buildX = u.buildX;
                ud.buildY = u.buildY;
                Integer bb = u.buildBody != null ? bodyIndex.get(u.buildBody) : null;
                ud.buildBody = bb != null ? bb : -1;
            }
            ud.parent = u.parent != null && !u.parent.dead ? u.parent.id : -1;
            if (u.queue != null) {
                ud.queue = new String[u.queue.items.size()];
                ud.queuePaid = new int[ud.queue.length * 2];
                for (int i = 0; i < ud.queue.length; i++) {
                    ud.queue[i] = u.queue.items.get(i).name();
                    int[] paid = u.queue.paidAt(i);
                    ud.queuePaid[i * 2] = paid[0];
                    ud.queuePaid[i * 2 + 1] = paid[1];
                }
                ud.progress = u.queue.progress;
            }
            uds.add(ud);
        }
        d.units = uds.toArray(new UnitData[0]);

        Json json = new Json();
        json.setOutputType(JsonWriter.OutputType.json);
        json.setUsePrototypes(false);
        return json.toJson(d);
    }

    public static World load() {
        if (!exists()) return null;
        return fromJson(file().readString());
    }

    public static World fromJson(String text) {
        Json json = new Json();
        json.setIgnoreUnknownFields(true);
        SaveData d = json.fromJson(SaveData.class, text);
        if (d == null) return null;

        if (d.rivals == 0 && d.hexR != spacerts.world.Galaxy.HEX_R) // positions would land in the wrong hexes
            throw new IllegalStateException("This save is from the older, smaller Hive layout and can't be loaded");
        World w = new World(d.seed, d.rivals, spacerts.world.Difficulty.parse(d.difficulty));
        w.time = d.time;
        w.threats.nextWave = d.nextWave;
        w.threats.wave = d.wave;
        w.initialAsteroids = d.initialAsteroids;
        w.victoryAnnounced = d.victoryAnnounced;

        for (int i = 0; i < d.factions.length && i < w.factions.size(); i++) {
            Faction f = w.factions.get(i);
            FactionData fd = d.factions[i];
            f.energy = fd.energy;
            f.metal = fd.metal;
            if (fd.rare != null) System.arraycopy(fd.rare, 0, f.rare, 0, Math.min(fd.rare.length, RareItem.COUNT));
            if (fd.techs != null) for (String t : fd.techs) f.techs.add(Tech.valueOf(t));
            f.researching = fd.researching != null ? Tech.valueOf(fd.researching) : null;
            f.researchProgress = fd.researchProgress;
            f.eliminated = fd.eliminated;
            f.hasFlag = fd.hasFlag;
            f.autoDefense = !fd.autoDefenseOff;
            f.flagX = fd.flagX;
            f.flagY = fd.flagY;
            if (fd.found != null) for (int k = 0; k < fd.found.length && k < f.found.length; k++) f.found[k] = fd.found[k];
            f.refinement = Math.max(0, Math.min(Faction.REFINEMENT_MAX, fd.refinement));
            for (int k = 0; k < f.found.length; k++) if (f.rare[k] > 0) f.found[k] = true;
        }
        if (d.explored != null && d.explored.length == w.player.vision.explored.length)
            w.player.vision.explored = d.explored.clone();

        List<Body> bodies = new ArrayList<>();
        for (BodyData bd : d.bodies) {
            Body b = new Body(Body.Kind.valueOf(bd.kind), bd.x, bd.y, bd.r);
            b.type = bd.type != null ? PlanetType.valueOf(bd.type) : null;
            b.name = bd.name != null ? bd.name : "";
            b.metal = bd.metal;
            b.maxMetal = bd.maxMetal;
            b.life = bd.life;
            b.guardRadius = bd.guardRadius;
            b.guardTimer = bd.guardTimer;
            b.maxGuards = bd.maxGuards;
            b.turretPoints = bd.turretPoints + bd.defenseLevel;
            b.shieldPoints = bd.shieldPoints;
            b.color.set(bd.cr, bd.cg, bd.cb, 1f);
            b.side = bd.side;
            b.lootNeutron = bd.lootNeutron;
            b.lootExotic = bd.lootExotic;
            b.giant = bd.giant;
            bodies.add(b);
            w.bodies.add(b);
        }
        // systems: names, archetypes, gate links
        w.pirateRespawn = d.pirateRespawn;
        w.ascended = d.ascended;
        if (d.sysNames != null)
            for (int i = 0; i < d.sysNames.length && i < w.galaxy.systems.size(); i++) {
                StarSystem s = w.galaxy.systems.get(i);
                s.name = d.sysNames[i] != null ? d.sysNames[i] : "";
                if (d.sysArch != null && d.sysArch[i] != null) s.archetype = SystemArchetype.valueOf(d.sysArch[i]);
            }
        for (int i = 0; i < d.bodies.length; i++) {
            Body b = bodies.get(i);
            if (d.bodies[i].link >= 0 && d.bodies[i].link < bodies.size()) b.link = bodies.get(d.bodies[i].link);
            if (b.isGate() && b.side >= 0) w.systemAt(b.x, b.y).gates[b.side] = b;
        }
        w.galaxy.indexBodies(w.bodies);

        Map<Integer, Unit> byId = new HashMap<>();
        int maxId = 0;
        for (UnitData ud : d.units) {
            Faction f = w.factions.get(ud.owner);
            w.nextId = ud.id;
            Unit u = w.spawn(ShipClass.valueOf(ud.cls), f, ud.x, ud.y);
            byId.put(u.id, u);
            maxId = Math.max(maxId, u.id);
            u.hp = ud.hp;
            u.shield = ud.shield;
            u.angle = ud.angle;
            u.radius = ud.radius;
            u.order = Order.valueOf(ud.order);
            u.tx = ud.tx;
            u.ty = ud.ty;
            u.anchorX = ud.anchorX;
            u.anchorY = ud.anchorY;
            u.cargo = ud.cargo;
            u.cargoEnergy = ud.cargoEnergy;
            u.harvestState = ud.harvestState;
            u.work = ud.work;
            u.abilityCd = ud.abilityCd;
            u.leaving = ud.leaving;
            u.retreating = ud.retreating;
            u.retreatOff = ud.retreatOff;
            u.autoTask = ud.autoTask;
            u.homeX = ud.homeX;
            u.homeY = ud.homeY;
            u.tier = Math.max(1, ud.tier);
            u.hasPost = ud.hasPost;
            u.postX = ud.postX;
            u.postY = ud.postY;
            u.hasRally = ud.hasRally;
            u.rallyX = ud.rallyX;
            u.rallyY = ud.rallyY;
            u.home = ud.home >= 0 && ud.home < bodies.size() ? bodies.get(ud.home) : null;
            u.body = ud.body >= 0 && ud.body < bodies.size() ? bodies.get(ud.body) : null;
            if (ud.buildCls != null) {
                u.buildCls = ShipClass.valueOf(ud.buildCls);
                u.buildX = ud.buildX;
                u.buildY = ud.buildY;
                u.buildBody = ud.buildBody >= 0 && ud.buildBody < bodies.size() ? bodies.get(ud.buildBody) : null;
                u.buildPaid = true;
            }
            if (u.queue != null && ud.queue != null) {
                for (int i = 0; i < ud.queue.length; i++) {
                    ShipClass q = ShipClass.valueOf(ud.queue[i]);
                    boolean has = ud.queuePaid != null && ud.queuePaid.length >= i * 2 + 2;
                    u.queue.add(q, has ? ud.queuePaid[i * 2] : q.s.energy, has ? ud.queuePaid[i * 2 + 1] : q.s.metal);
                }
                u.queue.progress = ud.progress;
            }
            if (u.order == Order.ATTACK) u.order = Order.IDLE; // targets are not saved
        }
        w.nextId = maxId + 1;
        w.flushPending();

        for (UnitData ud : d.units) {
            Unit u = byId.get(ud.id);
            if (ud.station >= 0) u.station = byId.get(ud.station);
            if (ud.parent >= 0) {
                Unit p = byId.get(ud.parent);
                if (p != null && p.drones != null) {
                    u.parent = p;
                    p.drones.add(u);
                } else u.kill(true);
            }
            Body h = u.home;
            if (h == null) continue;
            switch (u.cls) {
                case COLONY: h.colony = u; break;
                case OUTPOST: h.outpost = u; break;
                case SHIPYARD: h.shipyard = u; break;
                case TURRET: if (u.owner == w.natives) h.turrets.add(u); break;
                case GUARD: h.guards.add(u); break;
                default: break;
            }
        }
        // a drone that lost its parent was killed above; clean it up
        w.units.removeIf(u -> u.dead);
        w.computePopulation();
        w.updateVision();
        w.conquest.recount();
        return w;
    }

    // ------------------------------------------------------------------ autosave for long campaigns

    public static final String AUTOSAVE = "nebula_dominion_autosave.json";

    public static void autosave(World w) {
        Gdx.files.local(AUTOSAVE).writeString(toJson(w), false);
    }

    public static boolean autosaveExists() {
        return Gdx.files.local(AUTOSAVE).exists();
    }

    public static World loadAutosave() {
        if (!autosaveExists()) return null;
        return fromJson(Gdx.files.local(AUTOSAVE).readString());
    }
}
package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.ai.RivalAI;
import spacerts.systems.AutoDefense;
import spacerts.systems.CombatSystem;
import spacerts.systems.ConstructionSystem;
import spacerts.systems.EconomySystem;
import spacerts.systems.MovementSystem;
import spacerts.systems.ProductionSystem;
import spacerts.systems.StationRally;
import spacerts.systems.TacticsSystem;
import spacerts.systems.ThreatDirector;
import spacerts.systems.UnitBehavior;
import spacerts.units.ShipClass;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Whole simulation state plus the per-tick update order. */
public class World {
    /** Housing the mothership provides on its own. */
    public static final int BASE_HOUSING = 35;
    /** Brood Swarm: Broodlings released by a destroyed Hive. */
    public static final int BROODLINGS = 5;
    public static final Color WHITE = new Color(1, 1, 1, 1);
    public static final Color ALERT = new Color(1f, 0.4f, 0.35f, 1f);
    public static final Color GOOD = new Color(0.45f, 1f, 0.55f, 1f);
    public static final Color INFO = new Color(0.7f, 0.85f, 1f, 1f);

    public final long seed;
    /** How the game was set up (mode, slots, teams); identical on every machine of a LAN game except localSlot. */
    public final GameSetup setup;
    /** Number of empires besides the first slot (0 = Campaign). */
    public final int rivalCount;
    public final Difficulty difficulty;
    public final Random rng;
    /** Separate stream for scarce-find rolls so loot never shifts the battle randomness. */
    public final Random loot;
    public float time;

    public final List<Faction> factions = new ArrayList<>();
    /**
     * The empire played on this machine. Only HUD, fog rendering and messages may depend on it: in LAN games every
     * machine has a different one, so simulation logic must treat all human empires alike.
     */
    public final Faction player;
    public final Faction natives, pirates;
    public final List<Body> bodies = new ArrayList<>();
    public final List<Unit> units = new ArrayList<>();
    private final List<Unit> pending = new ArrayList<>();
    public final List<Effect> effects = new ArrayList<>();
    public final List<Ping> pings = new ArrayList<>();
    public final List<Message> messages = new ArrayList<>();
    /** The map areas: one square for classic games, the 91-hex Hive for the pirates-only mode (0 rivals). */
    public final Galaxy galaxy;
    public final SpatialHash hash;

    public final CombatSystem combat;
    public final UnitBehavior behavior;
    public final MovementSystem movement;
    public final EconomySystem economy;
    public final ProductionSystem production;
    public final ThreatDirector threats;
    public final ConstructionSystem construction;
    public final AutoDefense autoDefense;
    public final StationRally rally;
    public final TacticsSystem tactics;
    /** Hive conquest: hex control, hex income, the frontier. */
    public final spacerts.systems.ConquestSystem conquest;

    /** Pirates-only mode: seconds until the next pirate stronghold may rise (see PirateBases). */
    public float pirateRespawn;
    public int nextId = 1;
    public int initialAsteroids;
    public boolean gameOver, victoryAnnounced;
    /** The last enemy empire has fallen: the match is decided and the simulation stops (same tick on every machine). */
    public boolean matchOver;
    /** The empire (or a member of the team) that won, once matchOver. */
    public Faction winner;
    public float alertX, alertY, alertTime = -99;
    private float visionTimer;

    public World(long seed, int rivals) {
        this(seed, rivals, Difficulty.NORMAL);
    }

    /** The classic setup: one human plus `rivals` bots (0 rivals = the Campaign). */
    public World(long seed, int rivals, Difficulty difficulty) {
        this(rivals <= 0 ? GameSetup.campaign(seed, difficulty != null ? difficulty : Difficulty.NORMAL)
                : GameSetup.skirmish(seed, rivals, difficulty != null ? difficulty : Difficulty.NORMAL));
    }

    public World(GameSetup setup) {
        this.setup = setup;
        this.difficulty = setup.difficulty != null ? setup.difficulty : Difficulty.NORMAL;
        this.seed = setup.seed;
        boolean campaign = setup.mode == GameSetup.Mode.CAMPAIGN;
        int slots = campaign ? 1 : Math.max(1, Math.min(GameSetup.MAX_SLOTS, setup.slots.size()));
        this.rivalCount = campaign ? 0 : slots - 1;
        this.rng = new Random(seed * 31 + 7);
        this.loot = new Random(seed * 131 + 17);
        this.galaxy = campaign ? Galaxy.hive() : Galaxy.classic();
        // small cells: separation and weapon queries are short-range, and clumped fleets stay cheap
        this.hash = new SpatialHash(galaxy.width, galaxy.height, galaxy.hive ? 400f : 150f);
        Faction local = null;
        for (int i = 0; i < slots; i++) {
            GameSetup.Slot s = setup.slots.get(i);
            Color c = GameSetup.COLORS[Math.floorMod(s.colorIndex, GameSetup.COLORS.length)];
            Faction f = add(new Faction(factions.size(), s.name, c, s.human ? Faction.Kind.PLAYER : Faction.Kind.RIVAL));
            f.team = campaign ? 0 : s.team;
            f.difficulty = s.botDifficulty != null ? s.botDifficulty : Difficulty.NORMAL;
            if (i == setup.localSlot) local = f;
        }
        player = local != null ? local : factions.get(0);
        natives = add(new Faction(factions.size(), "Natives", new Color(0.55f, 0.9f, 0.45f, 1f), Faction.Kind.NATIVES));
        pirates = add(new Faction(factions.size(), "Pirates", new Color(0.78f, 0.78f, 0.72f, 1f), Faction.Kind.PIRATES));

        combat = new CombatSystem(this);
        behavior = new UnitBehavior(this);
        movement = new MovementSystem(this);
        economy = new EconomySystem(this);
        production = new ProductionSystem(this);
        threats = new ThreatDirector(this);
        construction = new ConstructionSystem(this);
        autoDefense = new AutoDefense(this);
        rally = new StationRally(this);
        tactics = new TacticsSystem(this);
        conquest = new spacerts.systems.ConquestSystem(this);
        for (Faction f : factions) if (f.kind == Faction.Kind.RIVAL) f.ai = new RivalAI(this, f);
    }

    private Faction add(Faction f) {
        factions.add(f);
        if (f.isMajor()) f.vision = new VisionGrid(galaxy.width, galaxy.height, galaxy.hive ? 1000f : 250f);
        return f;
    }

    /** Campaign (the pirates-only mode): no rival empires, the Hive galaxy, pirate strongholds. */
    public boolean piratesOnly() {
        return setup.mode == GameSetup.Mode.CAMPAIGN;
    }

    /** Hands a human empire to a bot (a LAN player left the game). */
    public void handToBot(Faction f) {
        if (f.kind != Faction.Kind.PLAYER) return;
        f.kind = Faction.Kind.RIVAL;
        f.difficulty = Difficulty.NORMAL;
        f.ai = new RivalAI(this, f);
        announce(f.name + " left the game - a bot takes over their empire.", INFO);
    }

    public StarSystem systemAt(float x, float y) {
        return galaxy.systemAt(x, y);
    }

    // ------------------------------------------------------------------ tick

    public void update(float dt) {
        if (matchOver) return; // the match is decided: everything stops
        time += dt;
        galaxy.indexBodies(bodies);
        hash.rebuild(units);

        visionTimer -= dt;
        if (visionTimer <= 0) {
            visionTimer = 0.2f;
            updateVision();
        }

        production.update(dt);
        economy.update(dt);
        combat.updateNatives(dt);
        threats.update(dt);
        for (Faction f : factions) if (f.ai != null && !f.eliminated) f.ai.update(dt);
        autoDefense.update(dt);
        conquest.update(dt);

        rally.update(); // Rally Stations: formation slots around each Mothership
        tactics.update(dt); // battle tactics: formations form up, march and fight

        // focus-fire bookkeeping: how many units are shooting at each target
        for (int i = 0, n = units.size(); i < n; i++) units.get(i).targetedBy = 0;
        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (!u.dead && u.target != null && !u.target.dead) u.target.targetedBy++;
        }

        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (u.dead) continue;
            u.dormant = conquest.dormant(u); // idle pirates in hexes nobody is near sleep
            if (u.slowTimer > 0) u.slowTimer -= dt;
            if (u.stunTimer > 0) { // Martyr Shockwave: frozen in place, weapons offline
                u.stunTimer -= dt;
                u.vx = u.vy = 0;
                continue;
            }
            if (!u.dormant) behavior.update(u, dt);
        }
        movement.tick();
        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (!u.dead && !u.dormant && u.stunTimer <= 0) movement.move(u, dt);
        }

        removeDead();
        flushPending();

        for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
            Effect e = it.next();
            e.ttl -= dt;
            if (e.ttl <= 0) it.remove();
        }
        for (Iterator<Ping> it = pings.iterator(); it.hasNext(); ) {
            Ping p = it.next();
            p.ttl -= dt;
            if (p.ttl <= 0) it.remove();
        }
        while (messages.size() > 80) messages.remove(0); // room for the cheat list and chat history

        computePopulation();
        for (int i = 0, n = factions.size(); i < n; i++) { // smoothed repair spending for the HUD
            Faction f = factions.get(i);
            if (dt > 0) f.repairSpend += (f.repairSpentTick / dt - f.repairSpend) * Math.min(1f, dt * 0.5f);
            f.repairSpentTick = 0;
        }
    }

    public void flushPending() {
        if (!pending.isEmpty()) {
            units.addAll(pending);
            pending.clear();
        }
    }

    private void removeDead() {
        // handleDeath may kill more units, so loop until stable
        boolean any = true;
        int guard = 0;
        while (any && guard++ < 4) {
            any = false;
            for (int i = 0; i < units.size(); i++) {
                Unit u = units.get(i);
                if (u.dead) {
                    units.remove(i--);
                    handleDeath(u);
                    any = true;
                }
            }
        }
    }

    private final float[] buoyTmp = new float[2];

    public void updateVision() {
        for (Faction f : factions) {
            f.detectors.clear();
            f.auras.clear();
            f.colonies.clear();
            f.docks.clear();
            if (f.vision != null) f.vision.beginFrame();
        }
        boolean teams = hasTeams();
        for (Unit u : units) {
            if (u.dead) continue;
            Faction f = u.owner;
            if (f.vision != null) {
                float vis = u.vision() * (galaxy.hive ? systemAt(u.x, u.y).archetype.visionMult() : 1f); // nebulae are murky
                vis += u.cls == ShipClass.COLONY ? u.radius : 0;
                f.vision.reveal(u.x, u.y, vis);
                if (teams && f.team != 0) // allies share vision
                    for (int i = 0, n = factions.size(); i < n; i++) {
                        Faction a = factions.get(i);
                        if (a != f && a.vision != null && a.team == f.team) a.vision.reveal(u.x, u.y, vis);
                    }
            }
            if (u.s.detector) f.detectors.add(u);
            if (u.s.aura > 0) f.auras.add(u);
            if (u.cls == ShipClass.COLONY && u.home != null) f.colonies.add(u);
            if (u.cls == ShipClass.COLONY || u.cls == ShipClass.MOTHERSHIP || u.cls == ShipClass.FACTORY || u.cls == ShipClass.SHIPYARD
                    || u.cls == ShipClass.REFINERY)
                f.docks.add(u);
        }
        float[] bp = buoyTmp;
        for (int i = 0, n = factions.size(); i < n; i++) { // colony buoys: vision beacons around each colony
            Faction f = factions.get(i);
            if (f.vision == null) continue;
            for (int c = 0, nc = f.colonies.size(); c < nc; c++) {
                Body b = f.colonies.get(c).home;
                for (int k = 1; k <= b.buoyRings; k++)
                    for (int j = 0; j < Body.BUOYS_PER_RING; j++) {
                        b.buoy(k, j, bp);
                        f.vision.reveal(bp[0], bp[1], Body.BUOY_VISION);
                        if (teams && f.team != 0)
                            for (int a = 0; a < n; a++) {
                                Faction al = factions.get(a);
                                if (al != f && al.vision != null && al.team == f.team) al.vision.reveal(bp[0], bp[1], Body.BUOY_VISION);
                            }
                    }
            }
        }
        for (int i = 0, n = factions.size(); i < n; i++) { // ISeeDeadPeople cheat: no fog
            Faction f = factions.get(i);
            if (f.noFog && f.vision != null) f.vision.revealAll();
        }
        for (Ping p : pings)
            for (int i = 0, n = factions.size(); i < n; i++) {
                Faction a = factions.get(i);
                if (a.vision != null && (a == p.owner || (teams && !a.hostileTo(p.owner)))) a.vision.reveal(p.x, p.y, p.r);
            }
    }

    /** True when any empire is on a team (alliances are in play). */
    public boolean hasTeams() {
        for (int i = 0, n = factions.size(); i < n; i++) if (factions.get(i).team != 0) return true;
        return false;
    }

    public void computePopulation() {
        for (Faction f : factions) {
            f.popUsed = 0;
            f.popCap = (f.mothership != null && !f.mothership.dead) ? BASE_HOUSING : 0;
            f.upkeepPoints = 0;
        }
        for (Unit u : units) {
            if (u.dead) continue;
            Faction f = u.owner;
            if (!u.s.internal) f.popUsed += u.s.pop;
            if (!u.s.internal) f.upkeepPoints += u.cls.isStation() ? EconomySystem.UPKEEP_PER_STATION : u.s.pop;
            if (u.cls == ShipClass.COLONY && u.home != null) f.popCap += (int) u.home.housing(f);
            if (u.queue != null)
                for (ShipClass c : u.queue.items) f.popUsed += c.s.pop * c.s.squad;
            if (u.buildCls != null) f.popUsed += u.buildCls.s.pop; // Mender under construction
        }
    }

    // ------------------------------------------------------------------ spawning & death

    public Unit spawn(ShipClass c, Faction f, float x, float y) {
        Unit u = new Unit(nextId++, c, f, clampX(x, y), clampY(x, y));
        pending.add(u);
        if (c == ShipClass.MOTHERSHIP) f.mothership = u;
        if (c == ShipClass.HUB) f.hub = u;
        return u;
    }

    /** (x, y) pulled inside the system it belongs to (40 from the edge). */
    public float clampX(float x, float y) {
        return galaxy.systemAt(x, y).clampX(x, y);
    }

    public float clampY(float x, float y) {
        return galaxy.systemAt(x, y).clampY(x, y);
    }

    /** Metal a destroyed ship leaves behind as salvage: a quarter of its metal cost (fixed values for pirates and natives). */
    public static float salvageValue(Unit u) {
        switch (u.cls) {
            case RAIDER: return 40;
            case GUNSHIP: return 160;
            case DREADNOUGHT: return 1200;
            case GUARD: return 30;
            case PIRATE_BASE: return u.tier >= 3 ? 4000 : 1500; // tier 3 = the Pirate Citadel
            default:
                if (u.cls.isBuilding() || u.s.harvester || u.cls == ShipClass.DRONE) return 0;
                return u.s.metal / (float) u.s.squad * SALVAGE_SHARE;
        }
    }

    /** The Ascension Gate is complete: the galaxy is won. Raids drop to a trickle; play can continue. */
    public boolean ascended;
    /** Cheats: ItVexesMe (no victory) and DayLightSavings (raid timer frozen). */
    public boolean noVictory, raidsFrozen;

    public void ascend(Faction f, Unit gate) {
        if (noVictory) { // ItVexesMe cheat
            notify(f, "The Ascension Gate stands, but victory is disabled (ItVexesMe).", INFO);
            return;
        }
        ascended = true;
        ring(gate.x, gate.y, 2400, GOOD, 2.5f);
        announce("THE ASCENSION GATE IS COMPLETE! The pirate clans are broken - the galaxy is yours. (Keep playing if you like.)", GOOD);
    }

    /** Share of a destroyed ship's metal cost left in its wreck (metal is the limited resource). */
    public static final float SALVAGE_SHARE = 0.5f;

    /** Gives f a rare item; scarce finds are announced. */
    public void grantRare(Faction f, spacerts.research.RareItem item, int n, float x, float y) {
        if (n <= 0) return;
        f.rare[item.ordinal()] += n;
        f.found[item.ordinal()] = true;
        if (f != player) return;
        text(x, y + 22, "+" + n + " " + item.shortName, item.color);
        if (item.scarce()) notify(f, "Rare find! +" + n + " " + item.title + ".", GOOD);
    }

    /** Martyr Shockwave: radius and stun duration. */
    public static final float SHOCKWAVE_RADIUS = 1200f, SHOCKWAVE_STUN = 8f;

    /** A fallen colony's last act: stuns every enemy ship (not structures) around it. */
    public void shockwave(Faction f, float x, float y) {
        int stunned = 0;
        for (Unit e : units) {
            if (e.dead || e.owner == f || e.s.stationary || e.dist(x, y) > SHOCKWAVE_RADIUS) continue;
            e.stunTimer = SHOCKWAVE_STUN;
            e.target = null;
            stunned++;
        }
        Color c = new Color(0.55f, 0.8f, 1f, 1f);
        ring(x, y, SHOCKWAVE_RADIUS, c, 1.6f);
        ring(x, y, SHOCKWAVE_RADIUS * 0.6f, c, 1.1f);
        blast(x, y, 260, c);
        if (stunned > 0) notify(f, "Martyr Shockwave! " + stunned + " enemy ships stunned for " + (int) SHOCKWAVE_STUN + " s.", GOOD);
    }

    /** Leaves a salvageable wreck where u died, merging into a wreck already close by. */
    private void spawnWreck(Unit u) {
        float value = salvageValue(u);
        if (value < 10) return;
        // scarce loot riding in the wreck, collected by whoever salvages it
        int neutron = u.cls == ShipClass.PIRATE_BASE ? (u.tier >= 3 ? 5 : 2) : 0;
        int exotic = u.cls == ShipClass.DREADNOUGHT && rng.nextFloat() < 0.25f ? 1 : (u.cls == ShipClass.PIRATE_BASE && u.tier >= 3 ? 3 : 0);
        for (Body b : bodies) {
            if (b.dead || !b.isWreck() || Math.hypot(b.x - u.x, b.y - u.y) > 60) continue;
            b.metal += value;
            b.maxMetal += value;
            b.life = Math.max(b.life, Body.WRECK_LIFE);
            b.radius = Math.min(30f, b.radius + 2f);
            b.lootNeutron += neutron;
            b.lootExotic += exotic;
            return;
        }
        Body b = new Body(Body.Kind.WRECK, clampX(u.x, u.y), clampY(u.x, u.y), Math.max(8f, Math.min(30f, 8f + value / 20f)));
        b.metal = b.maxMetal = value;
        b.life = Body.WRECK_LIFE;
        b.lootNeutron = neutron;
        b.lootExotic = exotic;
        bodies.add(b);
    }

    private void handleDeath(Unit u) {
        if (!u.silent) {
            float size = Math.max(u.radius * 2.5f, 30f);
            blast(u.x, u.y, size, u.owner.color);
            if (u.radius > 30) ring(u.x, u.y, size * 2f, new Color(1f, 0.8f, 0.5f, 1f), 0.9f);
        }
        if (!u.silent) spawnWreck(u);
        Faction f = u.owner;
        if (u.buildCls != null) {
            if (!u.silent && f == player) notify(f, "Harvester lost - construction of " + u.buildCls.s.name + " refunded.", ALERT);
            u.cancelBuild();
        }
        switch (u.cls) {
            case MOTHERSHIP:
                if (f.noDefeat && !f.eliminated) // StrengthAndHonor cheat: the empire fights on without its flagship
                    notify(f, "Strength and Honor: your Mothership fell, but your empire fights on.", ALERT);
                else eliminate(f);
                break;
            case PIRATE_BASE:
                if (!u.silent) PirateBases.onBaseDestroyed(this, u);
                break;
            case HUB:
                if (f.hub == u) {
                    f.hub = null;
                    if (f.researching != null) {
                        notify(f, "Research Hub destroyed! Research of " + f.researching.title + " lost.", ALERT);
                        f.researching = null;
                        f.researchProgress = 0;
                    } else notify(f, "Research Hub destroyed!", ALERT);
                }
                break;
            case COLONY:
                if (u.home != null) {
                    Body p = u.home;
                    if (p.colony == u) {
                        p.colony = null;
                        p.turretPoints = 0;
                        p.shieldPoints = 0;
                    }
                    for (Unit o : units)
                        if (!o.dead && o.home == p && o.owner == f && (o.cls == ShipClass.TURRET || o.cls == ShipClass.SHIPYARD))
                            o.kill(false);
                    if (!u.silent) notify(f, "Colony " + p.name + " has been destroyed!", ALERT);
                    if (!u.silent && f.has(spacerts.research.Tech.MARTYR_SHOCKWAVE)) shockwave(f, u.x, u.y);
                }
                break;
            case PATROL: // splits into skiffs that keep the patrol going
                if (!u.silent) {
                    int pieces = f.has(spacerts.research.Tech.SPLINTER_HULLS) ? 3 : 2;
                    for (int i = 0; i < pieces; i++) {
                        double a = Math.PI * 2 * i / pieces + u.angle;
                        Unit s = spawn(ShipClass.PATROL_SKIFF, f, u.x + (float) Math.cos(a) * 18, u.y + (float) Math.sin(a) * 18);
                        s.patrolStop = u.patrolStop;
                        s.retreatOff = true; // skiffs fight to the end
                        s.angle = (float) a;
                    }
                }
                break;
            case HIVE: // Brood Swarm: a destroyed carrier releases Broodlings that keep fighting
                if (!u.silent && f.has(spacerts.research.Tech.BROOD_SWARM)) {
                    for (int i = 0; i < BROODLINGS; i++) {
                        double a = Math.PI * 2 * i / BROODLINGS + u.angle;
                        Unit b = spawn(ShipClass.BROODLING, f, u.x + (float) Math.cos(a) * 30, u.y + (float) Math.sin(a) * 30);
                        b.angle = (float) a;
                        b.retreatOff = true; // they fight to the end
                        if (u.target != null && !u.target.dead) b.command(spacerts.units.Order.ATTACK_MOVE, u.target.x, u.target.y);
                    }
                    ring(u.x, u.y, u.radius * 3f, f.color, 0.5f);
                    notify(f, "Brood Swarm: your fallen Hive released " + BROODLINGS + " Broodlings.", INFO);
                }
                break;
            case OUTPOST:
                if (u.home != null && u.home.outpost == u) {
                    u.home.outpost = null;
                    if (!u.silent) notify(f, "Research Outpost on " + u.home.name + " destroyed!", ALERT);
                }
                break;
            case SHIPYARD:
                if (u.home != null && u.home.shipyard == u) u.home.shipyard = null;
                break;
            case TURRET:
                if (f == natives && u.home != null && !u.home.guarded()) {
                    Body p = u.home;
                    for (Unit g : p.guards) g.kill(false);
                    p.guards.clear();
                    if (player.vision.isExplored(p.x, p.y))
                        announce(p.name + "'s native defenses have fallen - it can now be " +
                                (p.habitable() ? "colonized." : "surveyed."), GOOD);
                }
                break;
            default:
                break;
        }
    }

    public void eliminate(Faction f) {
        if (f.eliminated) return;
        f.eliminated = true;
        f.researching = null;
        for (Unit o : units) if (o.owner == f && !o.dead) o.kill(false);
        for (Unit o : pending) if (o.owner == f) o.kill(true);
        if (f == player) {
            gameOver = true;
            announce("Your mothership has been destroyed.", ALERT);
        } else if (f.isMajor()) {
            announce(f.name + " has been eliminated!", f.hostileTo(player) ? GOOD : ALERT);
        }
        if (!f.isMajor() || victoryAnnounced || piratesOnly() || noVictory) return; // ItVexesMe cheat: no victory
        // victory: the empires still standing are all on one side
        Faction first = null;
        boolean oneSide = true;
        for (Faction o : factions) {
            if (!o.isMajor() || o.eliminated) continue;
            if (first == null) first = o;
            else if (first.hostileTo(o)) oneSide = false;
        }
        if (first == null || !oneSide) return;
        victoryAnnounced = true;
        matchOver = true;
        winner = first;
        String who = first.team != 0 && countTeam(first.team) > 1 ? "Team " + first.team : first.name;
        if (!player.eliminated && !player.hostileTo(first))
            announce("VICTORY! " + who + " rules the galaxy.", GOOD);
        else announce(who + " has won the game.", ALERT);
    }

    public int countTeam(int team) {
        int n = 0;
        for (Faction o : factions) if (o.isMajor() && o.team == team) n++;
        return n;
    }

    public void colonize(Body p, Faction f) {
        Unit c = spawn(ShipClass.COLONY, f, p.x, p.y);
        c.radius = p.radius + 8;
        c.home = p;
        p.colony = c;
        if (f.has(spacerts.research.Tech.PLANET_TURRETS)) spawnPlanetTurrets(p, f);
        ring(p.x, p.y, p.radius * 2.5f, f.color, 1.2f);
        boolean settlers = f.isMajor();
        if (settlers) { // every new colony comes with a harvester that starts working straight away
            double a = rng.nextDouble() * Math.PI * 2;
            Unit h = spawn(ShipClass.HARVESTER, f, p.x + (float) Math.cos(a) * (p.radius + 30), p.y + (float) Math.sin(a) * (p.radius + 30));
            economy.autoAssign(h);
        }
        notify(f, "Colony established on " + p.name + " (+" + (int) p.housing(f) + " housing" + (settlers ? ", +1 harvester)" : ")"), GOOD);
    }

    public void spawnPlanetTurrets(Body p, Faction f) {
        spawnPlanetTurrets(p, f, 0.7f);
    }

    /** Two turrets on opposite sides of the planet, rotated by angle so successive pairs don't overlap. */
    public void spawnPlanetTurrets(Body p, Faction f, float angle) {
        for (int i = 0; i < 2; i++) {
            double a = Math.PI * i + angle;
            Unit t = spawn(ShipClass.TURRET, f, p.x + (float) Math.cos(a) * (p.radius + 40), p.y + (float) Math.sin(a) * (p.radius + 40));
            t.home = p;
        }
    }

    // ------------------------------------------------------------------ queries

    public boolean canSee(Faction f, Unit t) {
        if (t.owner == f || (f.team != 0 && t.owner.team == f.team)) return true;
        if (f.vision != null && !f.vision.isVisible(t.x, t.y)) return false;
        return !t.cloaked(time) || isDetected(t, f);
    }

    public boolean isDetected(Unit t, Faction f) {
        for (Ping p : pings)
            if (!f.hostileTo(p.owner) && t.dist(p.x, p.y) < p.r) return true;
        if (detects(f, t)) return true;
        if (f.team != 0) // allied detectors count too
            for (int i = 0, n = factions.size(); i < n; i++) {
                Faction a = factions.get(i);
                if (a != f && a.team == f.team && detects(a, t)) return true;
            }
        return false;
    }

    private static boolean detects(Faction f, Unit t) {
        for (Unit d : f.detectors)
            if (!d.dead && t.dist(d) < d.vision() * 0.7f) return true;
        return false;
    }

    // ------------------------------------------------------------------ paid repairs

    /** A full repair from zero hull costs this fraction of the ship's metal price. */
    public static final float REPAIR_COST = 0.5f;
    /** Metal price used for the Mothership and other units that cost no metal. */
    public static final float REPAIR_BASIS_FREE = 1000f;

    /** Metal per point of hull repaired on u (0 for units whose repairs are free). */
    public static float repairPrice(Unit u) {
        if (!u.owner.isMajor() || u.s.internal || u.s.stationary) return 0; // ships, stations and the Mothership pay
        float basis = u.s.metal > 0 ? u.s.metal / (float) u.s.squad : REPAIR_BASIS_FREE;
        return REPAIR_COST * basis / Math.max(1f, u.maxHp());
    }

    /**
     * Repairs up to `amount` hull on u, paid in metal by its owner. Only what the owner can afford is repaired;
     * at 0 metal repairs stop (with an occasional warning). Returns the hull actually restored.
     */
    public float repair(Unit u, float amount) {
        amount = Math.min(amount, u.maxHp() - u.hp);
        if (amount <= 0) return 0;
        float price = repairPrice(u);
        Faction f = u.owner;
        if (price > 0) {
            float cost = amount * price;
            if (f.metal < cost) {
                amount = (float) Math.max(0, f.metal) / price;
                cost = amount * price;
                if (time - f.lastRepairAlert > 30f) {
                    f.lastRepairAlert = time;
                    notify(f, "Repairs halted: out of metal.", ALERT);
                }
            }
            if (amount <= 0) return 0;
            f.metal -= cost;
            f.repairSpentTick += cost;
        }
        u.hp += amount;
        return amount;
    }

    /** Nearest colony, factory, shipyard or mothership of f within max (edge distance), or null. */
    public Unit nearestRepairPoint(Faction f, float x, float y, float max) {
        return nearestRepairPoint(f, x, y, max, true);
    }

    /** As above; with colonies false, colonies are skipped (they no longer repair docked ships). */
    public Unit nearestRepairPoint(Faction f, float x, float y, float max, boolean colonies) {
        Unit best = null;
        float bd = max;
        for (Unit u : f.docks) {
            if (u.dead || (!colonies && u.cls == ShipClass.COLONY)) continue;
            float d = u.dist(x, y) - u.radius;
            if (d < bd) {
                bd = d;
                best = u;
            }
        }
        return best;
    }

    public Unit nearestDropoff(Faction f, float x, float y) {
        Unit best = null;
        float bd = Float.MAX_VALUE;
        StarSystem here = galaxy.hive ? systemAt(x, y) : null;
        for (Unit u : units) {
            if (u.dead || u.owner != f) continue;
            if (u.cls != ShipClass.MOTHERSHIP && u.cls != ShipClass.COLONY && u.cls != ShipClass.REFINERY) continue;
            float d = u.dist(x, y);
            if (here != null && systemAt(u.x, u.y) != here) d += 1e6f; // prefer a drop-off in the same system
            if (d < bd) {
                bd = d;
                best = u;
            }
        }
        return best;
    }

    public int count(Faction f, ShipClass c) {
        int n = 0;
        for (Unit u : units) if (!u.dead && u.owner == f && u.cls == c) n++;
        for (Unit u : pending) if (!u.dead && u.owner == f && u.cls == c) n++;
        return n;
    }

    public int queued(Faction f, ShipClass c) {
        int n = 0;
        for (Unit u : units)
            if (!u.dead && u.owner == f && u.queue != null)
                for (ShipClass q : u.queue.items) if (q == c) n++;
        return n;
    }

    public Body bodyAt(float x, float y, float slack) {
        Body best = null;
        float bd = Float.MAX_VALUE;
        for (Body b : bodies) {
            if (b.dead) continue;
            float d = (float) Math.hypot(b.x - x, b.y - y);
            if (d < b.radius + slack && d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ effects & messages

    public void beam(float x1, float y1, float x2, float y2, Color c, float width, float ttl) {
        effects.add(new Effect(Effect.Kind.BEAM, x1, y1, x2, y2, width, ttl, c, null));
    }

    public void blast(float x, float y, float size, Color c) {
        effects.add(new Effect(Effect.Kind.BLAST, x, y, 0, 0, size, 0.6f, c, null));
    }

    public void ring(float x, float y, float size, Color c, float ttl) {
        effects.add(new Effect(Effect.Kind.RING, x, y, 0, 0, size, ttl, c, null));
    }

    public void text(float x, float y, String s, Color c) {
        effects.add(new Effect(Effect.Kind.TEXT, x, y, 0, 0, 0, 2f, c, s));
    }

    /** Message shown only when the faction is the player. */
    public void notify(Faction f, String text, Color c) {
        if (f == player) messages.add(new Message(text, time, c));
    }

    public void announce(String text, Color c) {
        messages.add(new Message(text, time, c));
    }

    public void alert(float x, float y) {
        alertX = x;
        alertY = y;
        alertTime = time;
    }
}

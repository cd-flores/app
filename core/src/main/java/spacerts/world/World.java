package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.ai.RivalAI;
import spacerts.systems.AutoDefense;
import spacerts.systems.CombatSystem;
import spacerts.systems.ConstructionSystem;
import spacerts.systems.EconomySystem;
import spacerts.systems.MovementSystem;
import spacerts.systems.ProductionSystem;
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
    public static final Color WHITE = new Color(1, 1, 1, 1);
    public static final Color ALERT = new Color(1f, 0.4f, 0.35f, 1f);
    public static final Color GOOD = new Color(0.45f, 1f, 0.55f, 1f);
    public static final Color INFO = new Color(0.7f, 0.85f, 1f, 1f);

    private static final String[] RIVAL_NAMES = {"Krell Dominion", "Ashen Covenant", "Veyra Collective"};
    private static final Color[] RIVAL_COLORS = {
            new Color(1f, 0.33f, 0.3f, 1f), new Color(1f, 0.68f, 0.2f, 1f), new Color(0.85f, 0.42f, 1f, 1f)};

    public final long seed;
    public final int rivalCount;
    public final Difficulty difficulty;
    public final Random rng;
    /** Separate stream for scarce-find rolls so loot never shifts the battle randomness. */
    public final Random loot;
    public float time;

    public final List<Faction> factions = new ArrayList<>();
    public final Faction player, natives, pirates;
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
    /** Hive conquest: hex control, hex income, the frontier. */
    public final spacerts.systems.ConquestSystem conquest;

    /** Pirates-only mode: seconds until the next pirate stronghold may rise (see PirateBases). */
    public float pirateRespawn;
    public int nextId = 1;
    public int initialAsteroids;
    public boolean gameOver, victoryAnnounced;
    public float alertX, alertY, alertTime = -99;
    private float visionTimer;

    public World(long seed, int rivals) {
        this(seed, rivals, Difficulty.NORMAL);
    }

    public World(long seed, int rivals, Difficulty difficulty) {
        this.difficulty = difficulty != null ? difficulty : Difficulty.NORMAL;
        this.seed = seed;
        this.rivalCount = Math.max(0, Math.min(3, rivals));
        this.rng = new Random(seed * 31 + 7);
        this.loot = new Random(seed * 131 + 17);
        this.galaxy = rivalCount == 0 ? Galaxy.hive() : Galaxy.classic();
        // small cells: separation and weapon queries are short-range, and clumped fleets stay cheap
        this.hash = new SpatialHash(galaxy.width, galaxy.height, galaxy.hive ? 400f : 150f);
        player = add(new Faction(0, "Your Empire", new Color(0.3f, 0.8f, 1f, 1f), Faction.Kind.PLAYER));
        for (int i = 0; i < rivalCount; i++)
            add(new Faction(factions.size(), RIVAL_NAMES[i], RIVAL_COLORS[i], Faction.Kind.RIVAL));
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
        conquest = new spacerts.systems.ConquestSystem(this);
        for (Faction f : factions) if (f.kind == Faction.Kind.RIVAL) f.ai = new RivalAI(this, f);
    }

    private Faction add(Faction f) {
        factions.add(f);
        if (f.isMajor()) f.vision = new VisionGrid(galaxy.width, galaxy.height, galaxy.hive ? 1000f : 250f);
        return f;
    }

    /** Pirates-only mode: no rival empires, the Hive galaxy, pirate strongholds. */
    public boolean piratesOnly() {
        return rivalCount == 0;
    }

    public StarSystem systemAt(float x, float y) {
        return galaxy.systemAt(x, y);
    }

    // ------------------------------------------------------------------ tick

    public void update(float dt) {
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
        while (messages.size() > 40) messages.remove(0);

        computePopulation();
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

    public void updateVision() {
        for (Faction f : factions) {
            f.detectors.clear();
            f.auras.clear();
            f.colonies.clear();
            f.docks.clear();
            if (f.vision != null) f.vision.beginFrame();
        }
        for (Unit u : units) {
            if (u.dead) continue;
            Faction f = u.owner;
            if (f.vision != null) {
                float vis = u.vision() * (galaxy.hive ? systemAt(u.x, u.y).archetype.visionMult() : 1f); // nebulae are murky
                f.vision.reveal(u.x, u.y, vis + (u.cls == ShipClass.COLONY ? u.radius : 0));
            }
            if (u.s.detector) f.detectors.add(u);
            if (u.s.aura > 0) f.auras.add(u);
            if (u.cls == ShipClass.COLONY && u.home != null) f.colonies.add(u);
            if (u.cls == ShipClass.COLONY || u.cls == ShipClass.MOTHERSHIP || u.cls == ShipClass.FACTORY || u.cls == ShipClass.SHIPYARD
                    || u.cls == ShipClass.REFINERY)
                f.docks.add(u);
        }
        for (Ping p : pings) if (p.owner.vision != null) p.owner.vision.reveal(p.x, p.y, p.r);
    }

    public void computePopulation() {
        for (Faction f : factions) {
            f.popUsed = 0;
            f.popCap = (f.mothership != null && !f.mothership.dead) ? BASE_HOUSING : 0;
        }
        for (Unit u : units) {
            if (u.dead) continue;
            Faction f = u.owner;
            if (!u.s.internal) f.popUsed += u.s.pop;
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

    public void ascend(Faction f, Unit gate) {
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
                eliminate(f);
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
        } else if (f.kind == Faction.Kind.RIVAL) {
            announce(f.name + " has been eliminated!", GOOD);
            boolean anyLeft = false;
            for (Faction o : factions) if (o.kind == Faction.Kind.RIVAL && !o.eliminated) anyLeft = true;
            if (!anyLeft && !victoryAnnounced) {
                victoryAnnounced = true;
                announce("All rival empires destroyed! The galaxy is yours... but the pirates keep coming.", GOOD);
            }
        }
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
        if (t.owner == f) return true;
        if (f.vision != null && !f.vision.isVisible(t.x, t.y)) return false;
        return !t.cloaked(time) || isDetected(t, f);
    }

    public boolean isDetected(Unit t, Faction f) {
        for (Ping p : pings)
            if (p.owner == f && t.dist(p.x, p.y) < p.r) return true;
        for (Unit d : f.detectors)
            if (!d.dead && t.dist(d) < d.vision() * 0.7f) return true;
        return false;
    }

    /** Nearest colony, factory, shipyard or mothership of f within max (edge distance), or null. */
    public Unit nearestRepairPoint(Faction f, float x, float y, float max) {
        Unit best = null;
        float bd = max;
        for (Unit u : f.docks) {
            if (u.dead) continue;
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

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
    public static final float SIZE = 12000f;
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
    public float time;

    public final List<Faction> factions = new ArrayList<>();
    public final Faction player, natives, pirates;
    public final List<Body> bodies = new ArrayList<>();
    public final List<Unit> units = new ArrayList<>();
    private final List<Unit> pending = new ArrayList<>();
    public final List<Effect> effects = new ArrayList<>();
    public final List<Ping> pings = new ArrayList<>();
    public final List<Message> messages = new ArrayList<>();
    public final SpatialHash hash = new SpatialHash(SIZE, 250f);

    public final CombatSystem combat;
    public final UnitBehavior behavior;
    public final MovementSystem movement;
    public final EconomySystem economy;
    public final ProductionSystem production;
    public final ThreatDirector threats;
    public final ConstructionSystem construction;
    public final AutoDefense autoDefense;

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
        this.rivalCount = Math.max(1, Math.min(3, rivals));
        this.rng = new Random(seed * 31 + 7);
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
        for (Faction f : factions) if (f.kind == Faction.Kind.RIVAL) f.ai = new RivalAI(this, f);
    }

    private Faction add(Faction f) {
        factions.add(f);
        return f;
    }

    // ------------------------------------------------------------------ tick

    public void update(float dt) {
        time += dt;
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

        // focus-fire bookkeeping: how many units are shooting at each target
        for (int i = 0, n = units.size(); i < n; i++) units.get(i).targetedBy = 0;
        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (!u.dead && u.target != null && !u.target.dead) u.target.targetedBy++;
        }

        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (!u.dead) behavior.update(u, dt);
        }
        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (!u.dead) movement.move(u, dt);
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
            if (f.vision != null) f.vision.beginFrame();
        }
        for (Unit u : units) {
            if (u.dead) continue;
            Faction f = u.owner;
            if (f.vision != null) f.vision.reveal(u.x, u.y, u.vision() + (u.cls == ShipClass.COLONY ? u.radius : 0));
            if (u.s.detector) f.detectors.add(u);
            if (u.s.aura > 0) f.auras.add(u);
            if (u.cls == ShipClass.COLONY && u.home != null) f.colonies.add(u);
        }
        for (Ping p : pings) if (p.owner.vision != null) p.owner.vision.reveal(p.x, p.y, p.r);
    }

    public void computePopulation() {
        for (Faction f : factions) {
            f.popUsed = 0;
            f.popCap = (f.mothership != null && !f.mothership.dead) ? 15 : 0;
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
        Unit u = new Unit(nextId++, c, f, clampPos(x), clampPos(y));
        pending.add(u);
        if (c == ShipClass.MOTHERSHIP) f.mothership = u;
        if (c == ShipClass.HUB) f.hub = u;
        return u;
    }

    public static float clampPos(float v) {
        return Math.max(40f, Math.min(SIZE - 40f, v));
    }

    private void handleDeath(Unit u) {
        if (!u.silent) {
            float size = Math.max(u.radius * 2.5f, 30f);
            blast(u.x, u.y, size, u.owner.color);
            if (u.radius > 30) ring(u.x, u.y, size * 2f, new Color(1f, 0.8f, 0.5f, 1f), 0.9f);
        }
        Faction f = u.owner;
        if (u.buildCls != null) {
            if (!u.silent && f == player) notify(f, "Harvester lost - construction of " + u.buildCls.s.name + " refunded.", ALERT);
            u.cancelBuild();
        }
        switch (u.cls) {
            case MOTHERSHIP:
                eliminate(f);
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
        notify(f, "Colony established on " + p.name + " (+" + (int) p.housing(f) + " housing)", GOOD);
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

    public Unit nearestDropoff(Faction f, float x, float y) {
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for (Unit u : units) {
            if (u.dead || u.owner != f) continue;
            if (u.cls != ShipClass.MOTHERSHIP && u.cls != ShipClass.COLONY) continue;
            float d = u.dist(x, y);
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

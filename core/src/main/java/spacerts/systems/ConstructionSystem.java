package spacerts.systems;

import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.World;

/**
 * Harvesters as builders: Ship Factories and the Research Hub (placed on open space), planet defense
 * upgrades (on an owned colony) and Menders (built where the harvester stands).
 * The cost is paid when the order is given and refunded if the job is abandoned.
 */
public class ConstructionSystem {
    private final World w;

    /** Most Ship Stations an empire may have (alive plus under construction). */
    public static final int MAX_STATIONS = 2;
    /** Extra Ship Stations a tier II+ Mothership allows. */
    public static final int TIER2_STATIONS = 2;

    /** Ship Stations f may have: 2, or 4 with a tier II+ Mothership. */
    public static int maxStations(Faction f) {
        return MAX_STATIONS + (f.msTier() >= 2 ? TIER2_STATIONS : 0);
    }

    /** Research Hubs f may have: 1, or 2 with a tier II+ Mothership. */
    public static int maxHubs(Faction f) {
        return f.msTier() >= 2 ? 2 : 1;
    }

    public ConstructionSystem(World w) {
        this.w = w;
    }

    /** Reasons that don't depend on where the thing is built (tech, limits, housing), or null. */
    public String baseBlocker(Faction f, ShipClass c) {
        if (c.s.tech != null && !f.has(c.s.tech) && !f.synergy) return "Requires research";
        if (c.s.hiveOnly && !w.galaxy.hive) return "Only in the Hive galaxy (pirates mode)";
        if (c.s.msTier > f.msTier() && !f.synergy) return "Requires Mothership tier " + ProductionSystem.roman(c.s.msTier);
        if (c == ShipClass.HUB && f.hubsAlive() + building(f, ShipClass.HUB, null) >= maxHubs(f))
            return maxHubs(f) > 1 ? "At most 2 Research Hubs" : "Only one Research Hub (a second needs Mothership tier II)";
        if (c == ShipClass.FACTORY && w.count(f, ShipClass.FACTORY) + building(f, ShipClass.FACTORY, null) >= maxStations(f))
            return "At most " + maxStations(f) + " Ship Stations" + (maxStations(f) == MAX_STATIONS ? " (" + (MAX_STATIONS + TIER2_STATIONS) + " at Mothership tier II)" : "");
        if (c == ShipClass.COMMAND && (w.count(f, ShipClass.COMMAND) > 0 || building(f, ShipClass.COMMAND, null) > 0))
            return "Only one Command Center";
        if (c == ShipClass.ASCENSION && (w.count(f, ShipClass.ASCENSION) > 0 || building(f, ShipClass.ASCENSION, null) > 0))
            return "Only one Ascension Gate";
        if (c.s.pop > 0 && f.popUsed + c.s.pop > f.popCap && !f.noHousing) return "Not enough housing - colonize habitable planets";
        return null;
    }

    /**
     * The nearest harvester of f (not a barge) that isn't already building, preferring ones not fleeing a raid;
     * used when an upgrade is ordered from the colony itself. Null if there is none.
     */
    public Unit nearestFreeBuilder(Faction f, Body site) {
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for (Unit u : w.units) {
            if (u.dead || u.owner != f || u.cls != ShipClass.HARVESTER || u.buildCls != null) continue;
            float d = u.dist(site.x, site.y) + (u.fleeTimer > 0 ? 100000 : 0);
            if (d < bd) { bd = d; best = u; }
        }
        return best;
    }

    /** Full check for a specific harvester / target, or null when the order is allowed. */
    public String blocker(Unit h, ShipClass c, Body body, float x, float y) {
        if (h == null || h.dead || h.cls != ShipClass.HARVESTER) return "Select a harvester to build";
        Faction f = h.owner;
        String base = baseBlocker(f, c);
        if (base != null) return base;
        if (c.isPlanetUpgrade()) {
            if (body == null || !body.colonized() || body.colony.owner != f) return "Planet upgrades must be built on one of your colonies";
            if (pointsUsed(f, body) >= Body.MAX_DEFENSE)
                return body.name + " has used all " + Body.MAX_DEFENSE + " upgrade points";
        }
        if (c == ShipClass.SOLAR) {
            if (body == null || body.dead || body.kind != Body.Kind.STAR) return "Solar Collectors must be built next to a star";
            if (body.burntOut()) return body.name + " has burnt out";
            if (extractorsAt(body, ShipClass.SOLAR) >= SOLAR_PER_STAR) return body.name + " already has " + SOLAR_PER_STAR + " Solar Collectors";
        }
        if (c == ShipClass.MINER) {
            if (body == null || body.dead || !(body.kind == Body.Kind.ASTEROID || (body.isPlanet() && body.type.minable)))
                return "Asteroid Miners must be built next to an asteroid or a metal-rich planet";
            if (body.guarded()) return body.name + " is guarded - destroy its native turrets first";
            if (minerNear(body)) return "There is already a miner working here";
        }
        if (c == ShipClass.BEACON || c == ShipClass.ASCENSION) {
            spacerts.world.StarSystem s = w.systemAt(x, y);
            if (c == ShipClass.BEACON) {
                if (ConquestSystem.beaconIn(w, s) != null || buildingIn(f, ShipClass.BEACON, s)) return s.name + " already has a Claim Beacon";
                if (ConquestSystem.strongholdIn(w, s) != null) return "Destroy the pirate stronghold in " + s.name + " first";
            } else {
                if (s.archetype != spacerts.world.SystemArchetype.CITADEL) return "The Ascension Gate must be built in the Citadel hex at the top of the hive";
                Unit beacon = ConquestSystem.beaconIn(w, s);
                if (beacon == null || beacon.owner != f) return "Conquer the Citadel hex first (destroy the Citadel, then build a Claim Beacon there)";
            }
        }
        if (c.needsSite()) return siteBlocker(f, x, y, c.s.radius);
        return null;
    }

    /** True if one of f's harvesters is already on its way to build c inside system s. */
    private boolean buildingIn(Faction f, ShipClass c, spacerts.world.StarSystem s) {
        for (Unit u : w.units)
            if (!u.dead && u.owner == f && u.buildCls == c && w.systemAt(u.buildX, u.buildY) == s) return true;
        return false;
    }

    public static final int SOLAR_PER_STAR = 2;
    /** Energy per second from a Solar Collector, and metal per second from an Asteroid Miner (before tech bonuses). */
    public static final float SOLAR_RATE = 6f, MINER_RATE = 5f, MINER_PLANET_RATE = 4f, MINER_REACH = 450f;

    /** Built and in-progress extractors of type c at a body (any owner). */
    private int extractorsAt(Body b, ShipClass c) {
        int n = 0;
        for (Unit u : w.units)
            if (!u.dead && ((u.cls == c && u.home == b) || (u.buildCls == c && u.buildBody == b))) n++;
        return n;
    }

    /** True if a miner (built or being built) already works this asteroid field or planet. */
    private boolean minerNear(Body b) {
        float r = b.kind == Body.Kind.ASTEROID ? MINER_REACH : b.radius + 60;
        for (Unit u : w.units) {
            if (u.dead) continue;
            if (u.cls == ShipClass.MINER && u.home != null && Math.hypot(u.home.x - b.x, u.home.y - b.y) < r) return true;
            if (u.buildCls == ShipClass.MINER && u.buildBody != null && Math.hypot(u.buildBody.x - b.x, u.buildBody.y - b.y) < r) return true;
        }
        return false;
    }

    /** Whether a structure of radius r can be placed at (x, y). */
    public String siteBlocker(Faction f, float x, float y, float r) {
        if (!w.systemAt(x, y).inside(x, y, r + 60)) return "Too close to the edge of the map";
        if (f.vision != null && !f.vision.isExplored(x, y)) return "Can't build in unexplored space";
        for (Body b : w.bodies)
            if (!b.dead && !b.isWreck() && Math.hypot(b.x - x, b.y - y) < b.radius + r + 60) return "Too close to " + (b.kind == Body.Kind.ASTEROID ? "an asteroid" : b.name);
        for (Unit o : w.units)
            if (!o.dead && o.cls.isBuilding() && o.dist(x, y) < o.radius + r + 25) return "Too close to another structure";
        for (Unit o : w.units)
            if (!o.dead && o.buildCls != null && o.buildCls.needsSite() && Math.hypot(o.buildX - x, o.buildY - y) < o.buildCls.s.radius + r + 25)
                return "Another structure is being built there";
        return null;
    }

    /** Upgrade points spent on a planet, counting upgrades still under construction. */
    public int pointsUsed(Faction f, Body body) {
        return body.defensePoints() + building(f, ShipClass.DEF_TURRET, body) + building(f, ShipClass.DEF_SHIELD, body);
    }

    /** Number of harvesters of f currently assigned to build c (on the given body, if not null). */
    public int building(Faction f, ShipClass c, Body body) {
        int n = 0;
        for (Unit u : w.units)
            if (!u.dead && u.owner == f && u.buildCls == c && (body == null || u.buildBody == body)) n++;
        return n;
    }

    /** Gives harvester h a construction order. Returns an error message or null on success. */
    public String order(Unit h, ShipClass c, Body body, float x, float y) {
        if (c.isPlanetUpgrade() && body != null) {
            x = body.x;
            y = body.y;
        } else if (c.isExtractor() && body != null) {
            // on the body's edge, on the side facing the harvester
            float a = (float) Math.atan2(h.y - body.y, h.x - body.x);
            float d = body.radius + c.s.radius + 14;
            x = body.x + (float) Math.cos(a) * d;
            y = body.y + (float) Math.sin(a) * d;
        }
        String why = blocker(h, c, body, x, y);
        if (why != null) return why;
        Faction f = h.owner;
        String miss = f.missing(c.s.energy, c.s.metal, c.s.rare);
        if (miss != null) return miss;
        h.command(Order.BUILD, x, y); // refunds any job it was already doing
        f.pay(c.s.energy, c.s.metal, c.s.rare);
        h.buildCls = c;
        h.buildBody = c.targetsBody() ? body : null;
        h.buildX = x;
        h.buildY = y;
        h.buildPaid = true;
        h.work = 0;
        h.target = null;
        return null;
    }

    /** Distance at which the harvester starts working on its job. */
    public float workRange(Unit h) {
        ShipClass c = h.buildCls;
        if (c == null) return 0;
        if (c.isPlanetUpgrade() && h.buildBody != null) return h.buildBody.radius + 45;
        return c.s.radius + 35;
    }

    public void update(Unit h, float dt) {
        ShipClass c = h.buildCls;
        if (c == null) {
            h.idle();
            return;
        }
        Faction f = h.owner;
        if (c.isPlanetUpgrade()) {
            Body b = h.buildBody;
            if (b == null || !b.colonized() || b.colony.owner != f) {
                w.notify(f, "Defense construction cancelled - the colony was lost.", World.ALERT);
                h.idle();
                return;
            }
        }
        if (c.isExtractor() && (h.buildBody == null || h.buildBody.dead)) {
            w.notify(f, c.s.name + " cancelled - the asteroid was mined out.", World.ALERT);
            h.idle();
            return;
        }
        float range = workRange(h);
        if (h.dist(h.buildX, h.buildY) > range) {
            h.hasGoal = true;
            h.gx = h.buildX;
            h.gy = h.buildY;
            h.stopDist = range - 15;
            return;
        }
        h.work += dt * f.buildMult() * (f.warpTen ? 10f : 1f) / c.s.buildTime; // WarpTen cheat
        if (w.rng.nextFloat() < dt * 3f)
            w.beam(h.x, h.y, h.buildX + w.rng.nextFloat() * 30 - 15, h.buildY + w.rng.nextFloat() * 30 - 15, f.color, 1.5f, 0.2f);
        if (h.work >= 1f) complete(h);
    }

    private void complete(Unit h) {
        Faction f = h.owner;
        ShipClass c = h.buildCls;
        Body body = h.buildBody;
        float x = h.buildX, y = h.buildY;
        h.buildPaid = false; // finished: nothing to refund
        h.buildCls = null;
        h.buildBody = null;
        h.work = 0;
        switch (c) {
            case HUB: {
                Unit hub = w.spawn(ShipClass.HUB, f, x, y);
                w.ring(hub.x, hub.y, 150, f.color, 0.8f);
                w.notify(f, "Research Hub online. Select it (B) to research and build Surveyors.", World.GOOD);
                break;
            }
            case FACTORY: {
                Unit fac = w.spawn(ShipClass.FACTORY, f, x, y);
                w.ring(fac.x, fac.y, 150, f.color, 0.8f);
                w.notify(f, "Ship Station online. Select it to build warships (F2 cycles stations).", World.GOOD);
                break;
            }
            case MENDER: {
                Unit m = w.spawn(ShipClass.MENDER, f, x, y);
                w.ring(m.x, m.y, m.s.healRange, new com.badlogic.gdx.graphics.Color(0.4f, 1f, 0.5f, 1f), 0.8f);
                w.notify(f, "Repair Station online: it repairs every ally within " + (int) m.s.healRange + ".", World.GOOD);
                break;
            }
            case SOLAR:
            case MINER: {
                Unit e = w.spawn(c, f, x, y);
                e.home = body;
                e.angle = body != null ? (float) Math.atan2(body.y - y, body.x - x) : 0;
                w.ring(e.x, e.y, 90, f.color, 0.7f);
                w.notify(f, c.s.name + " online" + (body != null && !body.name.isEmpty() ? " at " + body.name : "") + ".", World.GOOD);
                break;
            }
            case DEF_TURRET:
            case DEF_SHIELD:
                if (body != null && body.colonized() && body.colony.owner == f) upgradePlanet(body, f, c);
                break;
            default: // the other placed structures: beacon, platform, refinery, sensor array, ion cannon, ascension gate
                if (c.needsSite()) {
                    Unit s = w.spawn(c, f, x, y);
                    w.ring(s.x, s.y, s.radius * 4f, f.color, 0.8f);
                    if (c == ShipClass.BEACON) w.notify(f, w.systemAt(x, y).name + " claimed!", World.GOOD);
                    else if (c == ShipClass.ASCENSION) w.ascend(f, s);
                    else w.notify(f, c.s.name + " online.", World.GOOD);
                }
                break;
        }
        h.idle();
        w.economy.autoAssign(h);
    }

    /** Spends one of the planet's upgrade points on turrets (+2 turrets) or shield (+400 shield, faster recharge). */
    public void upgradePlanet(Body b, Faction f, ShipClass kind) {
        if (b.defensePoints() >= Body.MAX_DEFENSE) return;
        if (kind == ShipClass.DEF_TURRET) {
            b.turretPoints++;
            w.spawnPlanetTurrets(b, f, 0.7f + b.turretPoints * 0.5236f); // evenly between the tech pair at 0.7
        } else {
            b.shieldPoints++;
            b.colony.shield = Math.min(b.colony.maxShield(), b.colony.shield + Body.SHIELD_PER_POINT * Unit.DURABILITY * Unit.COLONY_SHIELD_MULT); // the new layer comes online charged
        }
        w.ring(b.x, b.y, b.radius * 2.2f, kind == ShipClass.DEF_SHIELD ? new com.badlogic.gdx.graphics.Color(0.4f, 0.75f, 1f, 1f) : f.color, 1f);
        w.notify(f, b.name + ": " + kind.s.name + " done - turrets " + b.turretPoints + ", shield " + b.shieldPoints
                + " (" + b.defensePoints() + "/" + Body.MAX_DEFENSE + " points used)", World.GOOD);
    }
}

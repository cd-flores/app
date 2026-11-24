package spacerts.units;

import spacerts.world.Body;
import spacerts.world.Faction;

import java.util.ArrayList;
import java.util.List;

/** Every ship, structure, turret and colony in the game is a Unit. */
public class Unit {
    public final int id;
    public final ShipClass cls;
    public final Spec s;
    public final Faction owner;

    public float x, y, vx, vy, angle, radius;
    public float hp, shield;
    public boolean dead, silent;

    // orders
    public Order order = Order.IDLE;
    public float tx, ty;
    public Unit target;
    public Body body;
    public Body home;
    public float anchorX, anchorY;

    // combat
    public float cooldown, lastFired = -99, lastHit = -99, acquireTimer, abilityCd;
    public float tauntTimer, forcedTimer;
    public Unit forcedTarget;

    // movement goal (set each tick by behavior)
    public boolean hasGoal;
    public float gx, gy, stopDist;

    // harvester
    public int harvestState;
    public float cargo;
    public boolean cargoEnergy;

    // carrier
    public Unit parent;
    public final List<Unit> drones;
    public float droneTimer;

    // production
    public ProductionQueue queue;
    public boolean hasRally;
    public float rallyX, rallyY;

    // construction (harvesters)
    public ShipClass buildCls;
    public Body buildBody;
    public float buildX, buildY;
    public boolean buildPaid;

    // misc
    public float work;
    public boolean leaving;
    /** Set by movement each tick while heading to one of its own colonies. */
    public boolean boosted;

    // player automation (AutoDefense)
    /** Sent out by auto-defense (autoTask) or pulled back to a Mender (retreating); returns to homeX/Y afterwards. */
    public boolean autoTask, retreating;
    public float homeX, homeY;
    /** Harvester is running from a raid while > 0. */
    public float fleeTimer;
    /** How many units are currently shooting at this one (recounted every tick, used for focus fire). */
    public int targetedBy;
    /** Game time this unit last survived a killing blow thanks to Last Stand. */
    public float lastStand = -999;

    /** Clears automation state; called whenever the player gives this unit an order. */
    public void clearAuto() {
        autoTask = false;
        retreating = false;
    }

    public Unit(int id, ShipClass cls, Faction owner, float x, float y) {
        this.id = id;
        this.cls = cls;
        this.s = cls.s;
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.anchorX = x;
        this.anchorY = y;
        this.radius = s.radius;
        this.hp = maxHp();
        this.shield = maxShield();
        this.angle = (id * 2.39996f) % 6.2832f;
        this.queue = s.producer ? new ProductionQueue() : null;
        this.drones = s.drones > 0 ? new ArrayList<>() : null;
    }

    public float maxHp() { return s.hp * owner.hpMult(cls); }
    public float maxShield() { return s.shield + shieldPoints() * Body.SHIELD_PER_POINT; }
    public float shieldRegen() { return s.shieldRegen + shieldPoints() * Body.REGEN_PER_POINT; }
    public float speed() { return s.speed * owner.speedMult(); }
    public float range() { return s.range; }
    /** Ships see 10% further than their base stat; structures don't. */
    public float vision() { return s.vision * owner.visionMult() * (cls.isBuilding() ? 1f : 1.1f); }

    /** Shield upgrade points spent on this colony's planet (0 for everything else). */
    private int shieldPoints() {
        return cls == ShipClass.COLONY && home != null ? home.shieldPoints : 0;
    }
    public float damage() { return s.damage * owner.dmgMult(); }
    public boolean armed() { return s.damage > 0; }
    public boolean cloaked(float time) { return s.cloak && time - lastFired > 3f; }

    public float dist(Unit o) { return (float) Math.hypot(o.x - x, o.y - y); }
    public float dist(float px, float py) { return (float) Math.hypot(px - x, py - y); }

    /** Rough military value used by AI and threat scaling. */
    public float power() {
        return (s.energy + s.metal) / (float) s.squad / 100f + (cls == ShipClass.DRONE ? 0.3f : 0f);
    }

    public void kill(boolean silently) {
        dead = true;
        silent = silently;
    }

    /** Abandons an unfinished construction job and refunds its cost. */
    public void cancelBuild() {
        if (buildCls != null && buildPaid) owner.refund(buildCls.s.energy, buildCls.s.metal, buildCls.s.rare);
        buildCls = null;
        buildBody = null;
        buildPaid = false;
    }

    public void idle() {
        cancelBuild();
        order = Order.IDLE;
        target = null;
        anchorX = x;
        anchorY = y;
    }

    public void command(Order o, float px, float py) {
        cancelBuild();
        order = o;
        tx = px;
        ty = py;
        target = null;
        body = null;
        anchorX = px;
        anchorY = py;
    }
}

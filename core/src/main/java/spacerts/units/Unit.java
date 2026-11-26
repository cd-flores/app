package spacerts.units;

import spacerts.world.Body;
import spacerts.world.Faction;

import java.util.ArrayList;
import java.util.List;

/** Every ship, structure, turret and colony in the game is a Unit. */
public class Unit {
    /** Global toughness multiplier for HP, shields and shield regen, so fights become long attrition battles. */
    public static final float DURABILITY = 5f;
    /** Warships pull back for repairs below RETREAT_AT of max HP and go back once healed to RETREAT_DONE. */
    public static final float RETREAT_AT = 0.25f, RETREAT_DONE = 0.9f;
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
    public float cooldown, lastFired = -99, lastHit = -99, acquireTimer;
    /** Seconds until each ability (by ordinal) can be used again. */
    public final float[] abilityCds = new float[Ability.values().length];
    /** Ghost Drive (scouts): cloaked, faster and unable to fire while > 0. */
    public float ghostTimer;
    /** Evasive Dash (snipers): sliding backward at (dashVx, dashVy) while > 0. */
    public float dashTimer, dashVx, dashVy;
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
    /** Game time of this unit's last jump-gate transit (short cooldown so it doesn't bounce straight back). */
    public float lastJump = -99;
    /** Harvester cargo came from an asteroid (scarce Dark Matter finds). */
    public boolean cargoAsteroid;
    /** Warp cruise (Hive): seconds spent spooling up, and whether the drive is engaged (4x speed). */
    public float warpCharge;
    public boolean warping;
    /** Asteroid Miner crawling to the next field. */
    public boolean relocating;
    /** Stunned (Martyr Shockwave): can't move, fire or act while > 0. Slowed (Jump Inhibitors): half speed while > 0. */
    public float stunTimer, slowTimer;
    /** Patrol Ships / skiffs: index of the next stop on the patrol route. */
    public int patrolStop;
    /** Pirate garrison ships: the stronghold they are stationed at (null for raiders). */
    public Unit station;
    /** Not simulated this tick (an idle pirate far from any empire's ships). */
    public boolean dormant;
    /** Movement caches: last separation push, and whether the current goal is near a planet. */
    public float sepX, sepY;
    public float planetGoalX = Float.NaN, planetGoalY = Float.NaN;
    public boolean planetGoal;
    /** Healers (Repair Station, Halcyon) pulse their repairs a few times a second. */
    public float healTimer;
    /** Pirate patrol fleets: the point they hold. */
    public boolean hasPost;
    public float postX, postY;
    /** Upgrade tier of a Mothership or Ship Factory (1-3); tier 3 marks the Pirate Citadel. */
    public int tier = 1;
    /** Rally Stations: this station holds the formation slot (slotX, slotY) around its Mothership this tick. */
    public boolean rallied;
    /** Tactics: the formation this ship belongs to (0 = none), and its role slot is kept by the TacticsSystem. */
    public int formation;
    /** Which enemy it picks among those in reach. */
    public Targeting targeting = Targeting.NEAREST;
    /** Shots fired (Pincer snipers switch nearest / farthest every 3). */
    public int shotCount;
    /** Extra slow (Crippling Line): speed x slowMult while slowMultTimer > 0. */
    public float slowMult = 1f, slowMultTimer;
    /** Martyr Charge: charging this enemy sniper until martyrTimer runs out, then exploding. */
    public Unit martyrTarget;
    public float martyrTimer;
    /** Ramming Plates: last time this ship was rammed. */
    public float lastRammed = -99;
    public float slotX, slotY;

    // player automation (AutoDefense)
    /** Sent out by auto-defense (autoTask) or pulled back to a Mender (retreating); returns to homeX/Y afterwards. */
    public boolean autoTask, retreating;
    public float homeX, homeY;
    /** The player switched auto-retreat off for this ship (R). */
    public boolean retreatOff;
    /** The player redirected this ship while it was retreating: don't auto-retreat again until it is repaired. */
    public boolean retreatOverridden;
    /** Harvester is running from a raid while > 0. */
    public float fleeTimer;
    /** How many units are currently shooting at this one (recounted every tick, used for focus fire). */
    public int targetedBy;
    /** Game time this unit last survived a killing blow thanks to Last Stand. */
    public float lastStand = -999;

    /** Clears automation state; called whenever the player gives this unit an order. */
    public void clearAuto() {
        autoTask = false;
        if (retreating && hp < maxHp() * RETREAT_DONE) retreatOverridden = true;
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

    public float maxHp() { return s.hp * owner.hpMult(cls) * DURABILITY * tierMult(); }
    public float maxShield() { return (s.shield + shieldPoints() * Body.SHIELD_PER_POINT) * DURABILITY * tierMult() * colonyShield(); }

    /** Shields recover at half speed everywhere... */
    public static final float SHIELD_REGEN_MULT = 0.5f;
    /** ...but conquered planets (colonies) carry 5x the shield, and recharge 5x as fast to match. */
    public static final float COLONY_SHIELD_MULT = 5f;

    private float colonyShield() {
        return cls == ShipClass.COLONY ? COLONY_SHIELD_MULT : 1f;
    }

    /** Mothership upgrades: +30% hull and shield per tier above I; the Pirate Citadel (tier 3 base) is 3x a stronghold. */
    private float tierMult() {
        if (cls == ShipClass.MOTHERSHIP) return 1f + 0.3f * (tier - 1);
        if (cls == ShipClass.PIRATE_BASE && tier >= 3) return 3f;
        return 1f;
    }

    /** Targets hit per volley: each Mothership tier adds a gun. */
    public int multi() {
        return s.multi + (cls == ShipClass.MOTHERSHIP || (cls == ShipClass.PIRATE_BASE && tier >= 3) ? tier - 1 : 0);
    }

    /** Sovereign Overcharge: rate of fire doubled while > 0. */
    public float overchargeTimer;
    public float shieldRegen() {
        return (s.shieldRegen + shieldPoints() * Body.REGEN_PER_POINT) * DURABILITY * SHIELD_REGEN_MULT * colonyShield();
    }
    public float speed() { return s.speed * owner.speedMult(); }
    public float range() { return s.range; }
    /** Ships see 10% further than their base stat; structures don't. */
    public float vision() { return s.vision * owner.visionMult() * (cls.isBuilding() ? 1f : 1.1f); }

    /** Shield upgrade points spent on this colony's planet (0 for everything else). */
    private int shieldPoints() {
        return cls == ShipClass.COLONY && home != null ? home.shieldPoints : 0;
    }
    public float damage() { return s.damage * owner.dmgMult(); }
    /** Can fire right now (scouts in Ghost Drive can't). */
    public boolean armed() { return s.damage > 0 && ghostTimer <= 0; }
    public boolean cloaked(float time) { return (s.cloak && time - lastFired > 3f) || ghostTimer > 0; }

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

package spacerts.systems;

import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Automation for the player's own empire (toggle with G). It only touches units that are idle or harvesting,
 * never ones the player is actively commanding:
 * - idle warships respond to enemies near your bases, sending roughly 1.5x the threat's strength,
 * - harvesters run from raiders and resume afterwards,
 * - badly damaged ships pull back to the nearest Mender or base and return once repaired. This part runs for every
 *   empire (rivals too) and ignores the G toggle; the player switches it off per ship with R.
 */
public class AutoDefense {
    public static final float GUARD_RADIUS = 1400f, RESPONSE_RANGE = 2500f, FLEE_RADIUS = 450f;

    private final World w;
    private float timer;
    private final List<Unit> assets = new ArrayList<>(), threats = new ArrayList<>(), tmp = new ArrayList<>();

    public AutoDefense(World w) {
        this.w = w;
    }

    public void update(float dt) {
        timer -= dt;
        if (timer > 0) return;
        timer = 1f;
        retreats();
        Faction p = w.player;
        if (p.eliminated || !p.autoDefense) return;
        harvestersFlee(p);
        defend(p);
        release(p);
    }

    /** Rough fighting strength from toughness and damage output (works for pirates, which have no build cost). */
    public static float combatValue(Unit u) {
        return (u.maxHp() + u.maxShield()) / 300f + u.s.dps() * u.owner.dmgMult() / 20f;
    }

    private static boolean hostile(Faction me, Unit e) {
        return !e.dead && e.owner != me && e.armed()
                && (e.owner.kind == Faction.Kind.RIVAL || e.owner.kind == Faction.Kind.PIRATES);
    }

    // ------------------------------------------------------------------ defend bases

    private void defend(Faction p) {
        assets.clear();
        threats.clear();
        for (Unit u : w.units) {
            if (u.dead) continue;
            if (u.owner == p) {
                if (u.cls == ShipClass.MOTHERSHIP || (u.cls.isBuilding() && u.cls != ShipClass.TURRET)) assets.add(u);
            } else if (hostile(p, u) && !u.s.stationary && w.canSee(p, u)) threats.add(u);
        }
        // keep only threats close to one of our bases
        threats.removeIf(e -> {
            for (Unit a : assets) if (a.dist(e) < GUARD_RADIUS + a.radius) return false;
            return true;
        });
        while (!threats.isEmpty()) {
            // cluster around the first remaining threat
            Unit seed = threats.get(0);
            float cx = 0, cy = 0, power = 0;
            int n = 0;
            for (int i = threats.size() - 1; i >= 0; i--) {
                Unit e = threats.get(i);
                if (e.dist(seed) > 700) continue;
                cx += e.x;
                cy += e.y;
                power += combatValue(e);
                n++;
                threats.remove(i);
            }
            cx /= n;
            cy /= n;
            dispatch(p, cx, cy, power * 2f); // send about twice the threat so the fight is decisive
        }
    }

    /** Sends idle warships (closest first) toward (x, y) until their power covers `need`. */
    private void dispatch(Faction p, float x, float y, float need) {
        float have = 0;
        for (Unit u : w.units)
            if (!u.dead && u.owner == p && u.autoTask && !u.retreating && Math.hypot(u.tx - x, u.ty - y) < 900) have += combatValue(u);
        if (have >= need) return;
        tmp.clear();
        for (Unit u : w.units) {
            if (u.dead || u.owner != p || !u.cls.isWarship() || !u.armed() || u.cls == ShipClass.MENDER) continue;
            if (u.order != Order.IDLE || u.autoTask || u.retreating || u.hp < u.maxHp() * 0.3f) continue;
            if (u.dist(x, y) > RESPONSE_RANGE) continue;
            tmp.add(u);
        }
        tmp.sort((a, b) -> Float.compare(a.dist(x, y), b.dist(x, y)));
        for (Unit u : tmp) {
            if (have >= need) break;
            u.homeX = u.anchorX;
            u.homeY = u.anchorY;
            u.command(Order.ATTACK_MOVE, x + w.rng.nextFloat() * 120 - 60, y + w.rng.nextFloat() * 120 - 60);
            u.autoTask = true;
            have += combatValue(u);
        }
    }

    /** Auto-tasked ships that finished their job (idle, nothing hostile nearby) go back where they were. */
    private void release(Faction p) {
        for (Unit u : w.units) {
            if (u.dead || u.owner != p || !u.autoTask || u.retreating) continue;
            if (u.order != Order.IDLE || u.target != null) continue;
            if (enemyNear(p, u.x, u.y, 900)) continue;
            u.command(Order.MOVE, u.homeX, u.homeY);
            u.autoTask = false;
        }
    }

    // ------------------------------------------------------------------ harvesters

    private void harvestersFlee(Faction p) {
        for (Unit u : w.units) {
            if (u.dead || u.owner != p || !u.s.harvester || u.order != Order.HARVEST) continue;
            if (enemyNear(p, u.x, u.y, FLEE_RADIUS)) {
                if (u.fleeTimer <= 0 && w.rng.nextFloat() < 0.5f) w.text(u.x, u.y + 14, "fleeing!", World.ALERT);
                u.fleeTimer = 8f; // refreshed while the threat stays close
            }
        }
    }

    // ------------------------------------------------------------------ retreat for repairs

    /**
     * Warships of every empire that drop below Unit.RETREAT_AT pull back to the nearest Mender or base
     * (colony, factory, mothership), repair there, then attack-move back to where they left the fight.
     */
    public void retreats() {
        for (Unit u : w.units) {
            if (u.dead || !u.owner.isMajor() || !u.cls.isWarship() || u.cls == ShipClass.MENDER) continue;
            float frac = u.hp / u.maxHp();
            if (frac >= Unit.RETREAT_DONE) u.retreatOverridden = false;
            if (u.retreating) {
                if (frac >= Unit.RETREAT_DONE) {
                    u.clearAuto();
                    u.command(Order.ATTACK_MOVE, u.homeX, u.homeY);
                } else if (u.order == Order.IDLE && !repairing(u)) {
                    // arrived but the Mender / base is gone: find another, or give up
                    if (!sendToRepair(u)) u.clearAuto();
                }
                continue;
            }
            if (u.retreatOff || u.retreatOverridden || frac >= Unit.RETREAT_AT) continue;
            if (!u.autoTask) {
                boolean parked = u.order == Order.IDLE;
                u.homeX = parked ? u.anchorX : u.x;
                u.homeY = parked ? u.anchorY : u.y;
            }
            if (sendToRepair(u)) {
                u.autoTask = false;
                u.retreating = true;
                if (u.owner == w.player) w.text(u.x, u.y + 16, "retreating", RETREAT_C);
            }
        }
    }

    private static final com.badlogic.gdx.graphics.Color RETREAT_C = new com.badlogic.gdx.graphics.Color(1f, 0.75f, 0.3f, 1f);

    /** True while u sits inside a Mender's field or a base's repair range. */
    private boolean repairing(Unit u) {
        Unit m = nearestMender(u.owner, u, 3000);
        if (m != null && m.dist(u) - u.radius < m.s.healRange) return true;
        return w.nearestRepairPoint(u.owner, u.x, u.y, UnitBehavior.REPAIR_RANGE) != null;
    }

    /** Orders u to the nearest Mender or base. Returns false if there is nowhere to go. */
    private boolean sendToRepair(Unit u) {
        Unit mender = nearestMender(u.owner, u, 3000);
        Unit dock = w.nearestRepairPoint(u.owner, u.x, u.y, 6000);
        if (mender == null && dock == null) return false;
        float a = w.rng.nextFloat() * 6.283f;
        if (mender != null && (dock == null || mender.dist(u) < dock.dist(u))) {
            float r = mender.s.healRange * 0.5f * w.rng.nextFloat();
            u.command(Order.MOVE, mender.x + (float) Math.cos(a) * r, mender.y + (float) Math.sin(a) * r);
        } else {
            float r = dock.radius + 60 + w.rng.nextFloat() * 160;
            u.command(Order.MOVE, dock.x + (float) Math.cos(a) * r, dock.y + (float) Math.sin(a) * r);
        }
        return true;
    }

    private Unit nearestMender(Faction p, Unit u, float max) {
        Unit best = null;
        float bd = max;
        for (Unit m : w.units)
            if (!m.dead && m.owner == p && m.cls == ShipClass.MENDER && m.dist(u) < bd) {
                bd = m.dist(u);
                best = m;
            }
        return best;
    }

    private boolean enemyNear(Faction p, float x, float y, float r) {
        w.hash.query(x, y, r + 60, tmp);
        for (Unit e : tmp) if (hostile(p, e) && e.dist(x, y) < r && w.canSee(p, e)) return true;
        return false;
    }
}

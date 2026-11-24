package spacerts.systems;

import com.badlogic.gdx.graphics.Color;
import spacerts.research.Tech;
import spacerts.units.Ability;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/** Per-unit order execution: targeting, chasing, abilities, colonizing, surveying, drones, healing. */
public class UnitBehavior {
    private final World w;
    private final List<Unit> tmp = new ArrayList<>();
    private static final Color HEAL = new Color(0.4f, 1f, 0.5f, 1f);

    public UnitBehavior(World w) {
        this.w = w;
    }

    public void update(Unit u, float dt) {
        u.hasGoal = false;

        // regeneration & timers
        if (u.shield < u.maxShield() && w.time - u.lastHit > 4f)
            u.shield = Math.min(u.maxShield(), u.shield + u.shieldRegen() * u.owner.shieldRegenMult() * dt);
        if (u.owner.has(Tech.MS_REPAIR) && (u.cls == ShipClass.MOTHERSHIP || u.cls.isBuilding()) && u.hp < u.maxHp())
            u.hp = Math.min(u.maxHp(), u.hp + (u.cls == ShipClass.MOTHERSHIP ? 12f : 4f) * dt);
        if (u.cooldown > 0) u.cooldown -= dt;
        if (u.abilityCd > 0) u.abilityCd -= dt;
        if (u.tauntTimer > 0) u.tauntTimer -= dt;
        if (u.forcedTimer > 0) {
            u.forcedTimer -= dt;
            if (u.forcedTarget != null && !u.forcedTarget.dead && u.armed()) u.target = u.forcedTarget;
        }
        if (u.target != null && (u.target.dead || !w.canSee(u.owner, u.target))) u.target = null;

        if (u.drones != null) maintainDrones(u, dt);

        if (u.cls == ShipClass.DRONE) { drone(u, dt); return; }
        if (u.s.harvester) { w.economy.harvester(u, dt); return; }
        if (u.order == Order.COLONIZE && u.s.colonizer) { colonize(u); return; }
        if (u.order == Order.SURVEY && u.s.surveyor) { survey(u); return; }
        if (u.owner.kind == Faction.Kind.NATIVES) { natives(u, dt); return; }
        if (u.s.healRate > 0) { mender(u, dt); return; }
        if (!u.owner.isPlayer() && u.s.ability != Ability.NONE) autoAbility(u);
        orders(u, dt);
    }

    // ------------------------------------------------------------------ generic orders

    private void orders(Unit u, float dt) {
        boolean armed = u.armed();
        switch (u.order) {
            case MOVE:
                goal(u, u.tx, u.ty, 6);
                if (u.dist(u.tx, u.ty) < u.radius + 10) u.idle();
                else if (armed && !u.s.mustStop) {
                    if (u.target == null || !inRange(u, u.target)) u.target = acquire(u, u.range(), null, 0);
                    if (u.target != null && inRange(u, u.target) && u.cooldown <= 0) w.combat.fire(u, u.target);
                }
                break;
            case ATTACK:
                if (u.target == null) {
                    u.idle();
                    break;
                }
                engage(u, u.target);
                break;
            case ATTACK_MOVE:
                if (armed) autoAcquire(u, dt, Math.max(u.range() + 100, u.vision() * 0.85f));
                if (u.target != null) engage(u, u.target);
                else {
                    goal(u, u.tx, u.ty, 6);
                    if (u.dist(u.tx, u.ty) < u.radius + 20) u.idle();
                }
                break;
            default: // IDLE (and stray orders)
                if (armed) autoAcquire(u, dt, u.s.stationary ? u.range() : u.range() + 250);
                if (u.target != null && !u.s.stationary
                        && Math.hypot(u.target.x - u.anchorX, u.target.y - u.anchorY) > 900 + u.range()) {
                    u.target = null;
                }
                if (u.target != null) engage(u, u.target);
                else if (!u.s.stationary && u.dist(u.anchorX, u.anchorY) > 30) goal(u, u.anchorX, u.anchorY, 8);
                break;
        }
    }

    private boolean inRange(Unit u, Unit t) {
        float d = u.dist(t) - t.radius;
        return d <= u.range() && d >= u.s.minRange;
    }

    private void engage(Unit u, Unit t) {
        float d = u.dist(t) - t.radius;
        if (d > u.range()) {
            if (!u.s.stationary) goal(u, t.x, t.y, u.range() * 0.85f + t.radius);
        } else if (d < u.s.minRange) {
            if (!u.s.stationary) {
                float dx = u.x - t.x, dy = u.y - t.y;
                float len = Math.max(1f, (float) Math.hypot(dx, dy));
                goal(u, u.x + dx / len * 250, u.y + dy / len * 250, 0);
            }
        } else if (u.cooldown <= 0) {
            w.combat.fire(u, t);
        }
    }

    private void autoAcquire(Unit u, float dt, float r) {
        u.acquireTimer -= dt;
        if (u.acquireTimer > 0) return;
        u.acquireTimer = 0.35f + w.rng.nextFloat() * 0.15f;
        if (u.forcedTimer > 0 && u.target != null) return;
        if (u.target != null && inRange(u, u.target)) return;
        Unit best = acquire(u, r, null, 0);
        if (best != null) u.target = best;
    }

    /**
     * Best hostile target within r of the unit. If centre != null only targets within cr of it are considered
     * (used by natives guarding their planet).
     */
    public Unit acquire(Unit u, float r, Body centre, float cr) {
        w.hash.query(u.x, u.y, r + 80, tmp);
        Unit best = null;
        float bestScore = Float.MAX_VALUE;
        boolean focus = focusFire(u);
        for (Unit c : tmp) {
            if (c.dead || c.owner == u.owner) continue;
            float d = u.dist(c) - c.radius;
            if (d > r) continue;
            if (centre != null && Math.hypot(c.x - centre.x, c.y - centre.y) > cr) continue;
            if (!w.canSee(u.owner, c)) continue;
            float score = d;
            if (focus && d <= u.range()) {
                // finish off what allies are already shooting, and wounded targets
                score -= Math.min(c.targetedBy, 5) * 45f;
                score -= (1f - c.hp / c.maxHp()) * 160f;
            }
            if (u.s.bonusVs != null && c.s.type == u.s.bonusVs) score -= 250;
            if (!c.armed()) score += 150;
            if (c.cls.isBuilding()) score += 100;
            if (c.tauntTimer > 0) score -= 400;
            if (d < u.s.minRange) score += 2000;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** Smarter targeting: the player's units (with automation on), all empire turrets, and rivals on Hard. */
    private boolean focusFire(Unit u) {
        Faction f = u.owner;
        if (f.isPlayer()) return f.autoDefense;
        if (u.cls == ShipClass.TURRET && f.isMajor()) return true;
        return f.kind == Faction.Kind.RIVAL && w.difficulty == spacerts.world.Difficulty.HARD;
    }

    private void goal(Unit u, float x, float y, float stop) {
        u.hasGoal = true;
        u.gx = x;
        u.gy = y;
        u.stopDist = stop;
    }

    // ------------------------------------------------------------------ abilities

    /** Returns a failure reason or null on success. */
    public String useAbility(Unit u) {
        if (u.s.ability == Ability.NONE) return "No ability";
        if (u.abilityCd > 0) return u.s.ability.title + " recharging (" + (int) Math.ceil(u.abilityCd) + "s)";
        switch (u.s.ability) {
            case PING:
                w.pings.add(new spacerts.world.Ping(u.owner, u.x, u.y, 1600, 8));
                w.ring(u.x, u.y, 1600, u.owner.color, 1.2f);
                u.abilityCd = u.owner.has(Tech.SENSORS) ? 18 : 25;
                if (u.owner.vision != null) u.owner.vision.reveal(u.x, u.y, 1600);
                return null;
            case TAUNT:
                w.hash.query(u.x, u.y, 560, tmp);
                for (Unit e : tmp)
                    if (!e.dead && e.owner != u.owner && e.armed() && e.dist(u) < 500) {
                        e.forcedTarget = u;
                        e.forcedTimer = 5f;
                    }
                u.tauntTimer = 5f;
                u.abilityCd = 20;
                w.ring(u.x, u.y, 500, new Color(1f, 0.85f, 0.3f, 1f), 0.6f);
                return null;
            case NOVA:
                if (!u.owner.has(Tech.NOVA_CANNON)) return "Requires Nova Cannon research";
                Unit t = u.target != null ? u.target : acquire(u, 950, null, 0);
                if (t == null) return "No target in range";
                w.combat.nova(u, t.x, t.y);
                u.abilityCd = 35;
                return null;
            default:
                return "No ability";
        }
    }

    private void autoAbility(Unit u) {
        if (u.abilityCd > 0) return;
        switch (u.s.ability) {
            case PING:
                if (u.target != null || w.time - u.lastHit < 2) useAbility(u);
                break;
            case TAUNT:
                if (u.target != null && u.shield < u.maxShield() * 0.6f) useAbility(u);
                break;
            case NOVA:
                if (u.target != null) useAbility(u);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ special roles

    private void colonize(Unit u) {
        Body b = u.body;
        if (b == null || b.dead) { u.idle(); return; }
        goal(u, b.x, b.y, b.radius + 20);
        if (u.dist(b.x, b.y) < b.radius + u.radius + 45) {
            if (b.colonizable()) {
                w.colonize(b, u.owner);
                u.kill(true);
            } else {
                w.notify(u.owner, b.name + " can't be colonized: " +
                        (b.guarded() ? "destroy its native turrets first." : "it is already settled."), World.ALERT);
                u.idle();
            }
        }
    }

    private void survey(Unit u) {
        Body b = u.body;
        if (b == null || b.dead) { u.idle(); return; }
        goal(u, b.x, b.y, b.radius + 15);
        if (u.dist(b.x, b.y) < b.radius + u.radius + 40) {
            if (b.canSurvey()) {
                float a = (float) Math.atan2(u.y - b.y, u.x - b.x);
                Unit o = w.spawn(ShipClass.OUTPOST, u.owner, b.x + (float) Math.cos(a) * b.radius * 0.8f, b.y + (float) Math.sin(a) * b.radius * 0.8f);
                o.home = b;
                b.outpost = o;
                w.ring(o.x, o.y, 120, u.owner.color, 0.8f);
                w.notify(u.owner, "Research Outpost established on " + b.name + " (" + b.type.rare.title + ")", World.GOOD);
                u.kill(true);
            } else {
                w.notify(u.owner, b.name + " can't be surveyed: " +
                        (b.guarded() ? "destroy its guardians first." : "it already has an outpost."), World.ALERT);
                u.idle();
            }
        }
    }

    private void natives(Unit u, float dt) {
        Body home = u.home;
        if (home == null) { orders(u, dt); return; }
        float gr = home.guardRadius;
        u.acquireTimer -= dt;
        if (u.acquireTimer <= 0) {
            u.acquireTimer = 0.4f;
            if (u.target == null || u.target.dead || Math.hypot(u.target.x - home.x, u.target.y - home.y) > gr + 150)
                u.target = acquire(u, u.s.stationary ? u.range() : gr + 200, home, gr);
        }
        if (u.target != null) engage(u, u.target);
        else if (!u.s.stationary) {
            float a = u.id * 1.3f + w.time * 0.15f;
            goal(u, home.x + (float) Math.cos(a) * (home.radius + 110), home.y + (float) Math.sin(a) * (home.radius + 110), 10);
        }
    }

    private void maintainDrones(Unit u, float dt) {
        u.drones.removeIf(d -> d.dead);
        if (u.drones.size() < u.s.drones) {
            u.droneTimer -= dt;
            if (u.droneTimer <= 0) {
                u.droneTimer = 4f;
                Unit d = w.spawn(ShipClass.DRONE, u.owner, u.x + w.rng.nextFloat() * 20 - 10, u.y + w.rng.nextFloat() * 20 - 10);
                d.parent = u;
                u.drones.add(d);
            }
        }
    }

    private void drone(Unit d, float dt) {
        Unit p = d.parent;
        if (p == null || p.dead) { d.kill(false); return; }
        if (p.target != null && !p.target.dead) d.target = p.target;
        else {
            d.acquireTimer -= dt;
            if (d.acquireTimer <= 0) {
                d.acquireTimer = 0.4f;
                if (d.target == null) d.target = acquire(d, 450, null, 0);
            }
        }
        if (d.target != null && d.target.dist(p) > 750) d.target = null;
        if (d.target != null) engage(d, d.target);
        else {
            float a = d.id * 1.7f + w.time * 1.2f;
            goal(d, p.x + (float) Math.cos(a) * (p.radius + 40), p.y + (float) Math.sin(a) * (p.radius + 40), 4);
        }
    }

    /** Mobile repair station: repairs every damaged ally (ships and structures) inside its zone at once. */
    private void mender(Unit u, float dt) {
        float r = u.s.healRange;
        w.hash.query(u.x, u.y, r + 120, tmp);
        for (Unit c : tmp) {
            if (c.dead || c.owner != u.owner || c == u || c.cls == ShipClass.DRONE) continue;
            if (c.dist(u) - c.radius > r || c.hp >= c.maxHp()) continue;
            c.hp = Math.min(c.maxHp(), c.hp + u.s.healRate * dt);
            if (w.rng.nextFloat() < dt * 1.5f) w.beam(u.x, u.y, c.x, c.y, HEAL, 1.5f, 0.25f);
        }
        switch (u.order) {
            case MOVE:
            case ATTACK_MOVE:
            case ATTACK:
                goal(u, u.tx, u.ty, 6);
                if (u.dist(u.tx, u.ty) < u.radius + 15) u.idle();
                break;
            default: // parked: stay where it was put
                if (u.dist(u.anchorX, u.anchorY) > 30) goal(u, u.anchorX, u.anchorY, 8);
                break;
        }
    }
}

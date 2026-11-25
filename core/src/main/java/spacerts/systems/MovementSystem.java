package spacerts.systems;

import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.StarSystem;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/** Steering toward goals with simple separation so groups don't stack. */
public class MovementSystem {
    private final World w;
    private final List<Unit> tmp = new ArrayList<>();

    public MovementSystem(World w) {
        this.w = w;
    }

    /**
     * Travel boosts (the highest one that applies, they don't stack): toward one of the ship's own colonies,
     * toward any other planet, and while closing in on an attack. Planet and attack boosts are for empires only.
     */
    public static final float BOOST_HOME = 2f, BOOST_PLANET = 1.75f, BOOST_ATTACK = 1.75f;
    /** Attack boost lasts until the ship is within weapon range plus this margin. */
    public static final float ATTACK_BOOST_MARGIN = 250f;

    public float boost(Unit u) {
        if (!u.hasGoal || u.cls == ShipClass.DRONE) return 1f;
        if (headingHome(u)) return BOOST_HOME;
        if (!u.owner.isMajor()) return 1f;
        if (closingIn(u) || headingToPlanet(u)) return BOOST_PLANET; // BOOST_ATTACK == BOOST_PLANET
        return 1f;
    }

    /** On an attack or attack-move order and not yet in weapon range of its target. */
    public boolean closingIn(Unit u) {
        if (u.order != Order.ATTACK && u.order != Order.ATTACK_MOVE) return false;
        if (u.target == null) return u.order == Order.ATTACK_MOVE;
        return u.dist(u.target) - u.target.radius > u.range() + ATTACK_BOOST_MARGIN;
    }

    /** True when the unit's destination is at (or near) any planet (cached until the goal moves). */
    public boolean headingToPlanet(Unit u) {
        if (Math.abs(u.gx - u.planetGoalX) < 40 && Math.abs(u.gy - u.planetGoalY) < 40) return u.planetGoal;
        u.planetGoalX = u.gx;
        u.planetGoalY = u.gy;
        u.planetGoal = scanPlanets(u);
        return u.planetGoal;
    }

    private boolean scanPlanets(Unit u) {
        java.util.List<Body> near = w.systemAt(u.gx, u.gy).bodies;
        for (int i = 0, n = near.size(); i < n; i++) {
            Body b = near.get(i);
            if (b.dead || !b.isPlanet()) continue;
            float r = b.radius + 350f;
            float dx = u.gx - b.x, dy = u.gy - b.y;
            if (dx * dx + dy * dy < r * r) return true;
        }
        return false;
    }

    /** True when the unit's current destination is at (or near) a colony its faction owns. */
    public boolean headingHome(Unit u) {
        for (Unit c : u.owner.colonies) {
            if (c.dead || c.home == null) continue;
            float r = c.home.radius + 350f;
            float dx = u.gx - c.x, dy = u.gy - c.y;
            if (dx * dx + dy * dy < r * r) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ jump gates (Hive galaxy)

    public static final float JUMP_COOLDOWN = 2f;
    /** Jump Inhibitors: how long enemy ships stay slowed after jumping into the player's hex. */
    public static final float INHIBIT_TIME = 8f;
    private static final com.badlogic.gdx.graphics.Color JUMP_C = new com.badlogic.gdx.graphics.Color(0.5f, 0.9f, 1f, 1f);

    /**
     * A goal in another system is reached through jump gates: steer for the next gate on the shortest path
     * and jump when touching it. Orders keep their real target, so this is re-evaluated every tick.
     */
    private void route(Unit u) {
        StarSystem here = w.systemAt(u.x, u.y), there = w.systemAt(u.gx, u.gy);
        if (here == there) return;
        Body gate = w.galaxy.nextGate(here, there);
        if (gate == null || gate.link == null) return;
        u.gx = gate.x;
        u.gy = gate.y;
        u.stopDist = 0;
        float cooldown = u.owner.has(spacerts.research.Tech.HYPERDRIVE) ? 0f : JUMP_COOLDOWN;
        if (u.dist(gate.x, gate.y) < gate.radius + u.radius + 14 && w.time - u.lastJump > cooldown) jump(u, gate);
    }

    /** A point `inward` past a gate, inside its own system, where jumping ships come out. */
    public static float[] exitPoint(World w, Body gate, float inward, float lateral) {
        StarSystem s = w.systemAt(gate.x, gate.y);
        float ax = s.cx - gate.x, ay = s.cy - gate.y;
        float len = Math.max(1f, (float) Math.hypot(ax, ay));
        ax /= len;
        ay /= len;
        return new float[]{gate.x + ax * inward - ay * lateral, gate.y + ay * inward + ax * lateral};
    }

    /** Moves u (and its drones) from `gate` to the paired gate in the neighbouring system. */
    public void jump(Unit u, Body gate) {
        Body to = gate.link;
        w.ring(gate.x, gate.y, gate.radius * 1.6f, JUMP_C, 0.5f);
        float[] p = exitPoint(w, to, to.radius + u.radius + 70 + w.rng.nextFloat() * 90, (w.rng.nextFloat() - 0.5f) * 140);
        u.x = p[0];
        u.y = p[1];
        u.lastJump = w.time;
        // Jump Inhibitors: enemies arriving in a hex the player owns are slowed for a while
        StarSystem dest = w.systemAt(to.x, to.y);
        if (u.owner != w.player && dest.owned() && w.player.has(spacerts.research.Tech.JUMP_INHIBITORS)) u.slowTimer = INHIBIT_TIME;
        if (u.drones != null)
            for (Unit d : u.drones) {
                if (d.dead) continue;
                d.x = u.x + (w.rng.nextFloat() - 0.5f) * 80;
                d.y = u.y + (w.rng.nextFloat() - 0.5f) * 80;
                d.lastJump = w.time;
            }
        w.ring(to.x, to.y, to.radius * 1.6f, JUMP_C, 0.5f);
    }

    // ------------------------------------------------------------------ warp cruise (Hive: EVE-scale distances)

    /** Warp engages for goals further than WARP_MIN, after WARP_SPOOL s of charging, at WARP_MULT x speed; it drops out within WARP_EXIT. */
    public static final float WARP_MIN = 3000f, WARP_EXIT = 1200f, WARP_SPOOL = 2f, WARP_MULT = 4f;
    private static final com.badlogic.gdx.graphics.Color WARP_C = new com.badlogic.gdx.graphics.Color(0.75f, 0.9f, 1f, 1f);

    /**
     * Ships crossing a hex spool up their warp drive and cruise at 4x speed; combat (a target, or being hit
     * in the last 5 s), a Jump Inhibitor slow, or arriving near the goal drops them out.
     */
    private void updateWarp(Unit u, float dt) {
        if (!w.galaxy.hive || u.cls == ShipClass.DRONE) {
            u.warping = false;
            u.warpCharge = 0;
            return;
        }
        float d = u.hasGoal ? (float) Math.hypot(u.gx - u.x, u.gy - u.y) : 0;
        boolean calm = u.hasGoal && u.target == null && w.time - u.lastHit > 5f && u.slowTimer <= 0;
        if (u.warping) {
            if (!calm || d < WARP_EXIT) { // drop out of warp
                u.warping = false;
                u.warpCharge = 0;
                w.ring(u.x, u.y, u.radius * 3f, WARP_C, 0.3f);
            }
            return;
        }
        if (calm && d > WARP_MIN) {
            u.warpCharge += dt;
            if (u.warpCharge >= WARP_SPOOL) {
                u.warping = true;
                w.ring(u.x, u.y, u.radius * 3f, WARP_C, 0.3f);
            }
        } else u.warpCharge = 0;
    }

    /** Tick counter used to stagger the separation pass. */
    private int frame;

    /** Call once per world tick. */
    public void tick() {
        frame++;
    }

    public void move(Unit u, float dt) {
        if (u.s.stationary) return;
        if (u.hasGoal && w.galaxy.hive) route(u);
        float dvx = 0, dvy = 0;
        float speed = u.speed();
        float boost = boost(u);
        u.boosted = boost > 1f;
        speed *= boost;
        if (u.retreating && u.owner.has(spacerts.research.Tech.EMERGENCY_THRUSTERS)) speed *= 1.6f; // damaged ships get away
        if (u.slowTimer > 0) speed *= 0.5f; // Jump Inhibitors
        updateWarp(u, dt);
        if (u.warping) speed *= WARP_MULT;
        if (u.hasGoal) {
            float dx = u.gx - u.x, dy = u.gy - u.y;
            float d = (float) Math.hypot(dx, dy);
            if (d > u.stopDist + 2) {
                float s = speed;
                float remain = d - u.stopDist;
                if (remain < speed * 0.5f) s = Math.max(speed * 0.25f, remain * 2f); // ease in
                dvx = dx / d * s;
                dvy = dy / d * s;
            }
        }

        // separation from friendly ships: recomputed every third tick per ship (staggered by id) and capped at a
        // handful of overlaps, so a 200-ship blob doesn't cost 200 x 200 checks every tick
        if (!u.s.harvester && u.cls != ShipClass.DRONE && (u.id + frame) % 3 == 0) {
            float sx = 0, sy = 0;
            int hits = 0;
            w.hash.query(u.x, u.y, u.radius * 2 + 80, tmp);
            for (int i = 0, n = tmp.size(); i < n && hits < 8; i++) {
                Unit o = tmp.get(i);
                if (o == u || o.dead || o.owner != u.owner || o.s.stationary || o.s.harvester || o.cls == ShipClass.DRONE) continue;
                float dx = u.x - o.x, dy = u.y - o.y;
                float min = (u.radius + o.radius) * 1.15f;
                float d2 = dx * dx + dy * dy;
                if (d2 < min * min) {
                    float d = (float) Math.sqrt(d2);
                    if (d < 0.01f) {
                        dx = (u.id % 7) - 3f;
                        dy = (u.id % 5) - 2f;
                        d = 1f;
                    }
                    float push = (min - d) / min;
                    sx += dx / d * push * 90f;
                    sy += dy / d * push * 90f;
                    hits++;
                }
            }
            u.sepX = sx;
            u.sepY = sy;
        }
        float sx = u.sepX, sy = u.sepY;

        float k = Math.min(1f, dt * 5f);
        u.vx += (dvx - u.vx) * k;
        u.vy += (dvy - u.vy) * k;
        // ships never leave their system except through a jump gate
        StarSystem sys = w.systemAt(u.x, u.y);
        float nx = u.x + (u.vx + sx) * dt, ny = u.y + (u.vy + sy) * dt;
        u.x = sys.clampX(nx, ny);
        u.y = sys.clampY(nx, ny);

        float sp2 = u.vx * u.vx + u.vy * u.vy;
        if (sp2 > 25f) {
            float target = (float) Math.atan2(u.vy, u.vx);
            float diff = target - u.angle;
            while (diff > Math.PI) diff -= (float) (Math.PI * 2);
            while (diff < -Math.PI) diff += (float) (Math.PI * 2);
            u.angle += diff * Math.min(1f, dt * 8f);
        } else if (u.target != null && !u.target.dead) {
            float target = (float) Math.atan2(u.target.y - u.y, u.target.x - u.x);
            float diff = target - u.angle;
            while (diff > Math.PI) diff -= (float) (Math.PI * 2);
            while (diff < -Math.PI) diff += (float) (Math.PI * 2);
            u.angle += diff * Math.min(1f, dt * 6f);
        }
    }
}

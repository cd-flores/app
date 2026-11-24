package spacerts.systems;

import spacerts.units.ShipClass;
import spacerts.units.Unit;
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

    /** Speed multiplier for ships flying toward one of their own colonies. */
    public static final float BOOST = 1.5f;

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

    public void move(Unit u, float dt) {
        if (u.s.stationary) return;
        float dvx = 0, dvy = 0;
        float speed = u.speed();
        u.boosted = u.hasGoal && u.cls != ShipClass.DRONE && headingHome(u);
        if (u.boosted) speed *= BOOST;
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

        // separation from friendly ships
        float sx = 0, sy = 0;
        if (!u.s.harvester && u.cls != ShipClass.DRONE) {
            w.hash.query(u.x, u.y, u.radius * 2 + 80, tmp);
            for (Unit o : tmp) {
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
                }
            }
        }

        float k = Math.min(1f, dt * 5f);
        u.vx += (dvx - u.vx) * k;
        u.vy += (dvy - u.vy) * k;
        u.x = World.clampPos(u.x + (u.vx + sx) * dt);
        u.y = World.clampPos(u.y + (u.vy + sy) * dt);

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

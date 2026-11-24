package spacerts.systems;

import com.badlogic.gdx.graphics.Color;
import spacerts.research.Tech;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.Ping;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/** Weapons fire, damage resolution, and native planet garrisons. */
public class CombatSystem {
    private final World w;
    private final List<Unit> tmp = new ArrayList<>();
    private static final Color NOVA = new Color(1f, 0.6f, 0.25f, 1f);

    public CombatSystem(World w) {
        this.w = w;
    }

    public void fire(Unit u, Unit t) {
        u.cooldown = u.s.cooldown;
        u.lastFired = w.time;
        hit(u, t, 1f);
        beamFx(u, t);
        if (u.s.piercing && u.s.mustStop && t.owner.isMajor()) revealSniper(u, t.owner);

        if (u.s.splash > 0) {
            w.hash.query(t.x, t.y, u.s.splash + 80, tmp);
            for (Unit c : tmp)
                if (c != t && !c.dead && c.owner != u.owner && c.dist(t) - c.radius < u.s.splash) hit(u, c, 0.5f);
            w.blast(t.x, t.y, u.s.splash * 0.6f, u.owner.color);
        }
        if (u.s.multi > 1) {
            int extra = u.s.multi - 1;
            w.hash.query(u.x, u.y, u.range() + 80, tmp);
            for (Unit c : tmp) {
                if (extra <= 0) break;
                if (c == t || c.dead || c.owner == u.owner || !w.canSee(u.owner, c)) continue;
                if (u.dist(c) - c.radius > u.range()) continue;
                hit(u, c, 1f);
                beamFx(u, c);
                extra--;
            }
        }
    }

    /** A sniper that fires gives away its position to the side it shot at for a few seconds (cloak included). */
    private void revealSniper(Unit u, Faction victim) {
        Ping p = new Ping(victim, u.x, u.y, u.radius + 120, 4f);
        p.sniper = true;
        w.pings.add(p);
        if (victim.vision != null) victim.vision.reveal(u.x, u.y, p.r);
        if (victim == w.player && w.time - victim.lastSniperAlert > 15f) {
            victim.lastSniperAlert = w.time;
            w.notify(victim, "Sniper fire! Enemy " + u.s.name + " revealed.", World.ALERT);
            w.alert(u.x, u.y);
        }
    }

    private void beamFx(Unit u, Unit t) {
        boolean heavy = u.s.piercing || u.cls == ShipClass.LEVIATHAN || u.cls == ShipClass.DREADNOUGHT;
        Color c = u.owner.color;
        if (u.s.piercing) c = new Color(1f, 1f, 0.85f, 1f);
        w.beam(u.x, u.y, t.x, t.y, c, heavy ? 3f : 1.5f, heavy ? 0.3f : 0.1f);
    }

    public void hit(Unit src, Unit t, float mult) {
        float dmg = src.damage() * mult;
        if (src.s.bonusVs != null && t.s.type == src.s.bonusVs) dmg *= src.s.bonusMult;
        applyDamage(t, dmg, src.s.piercing, src);
    }

    public void applyDamage(Unit t, float dmg, boolean piercing, Unit src) {
        if (t.dead) return;
        if (!piercing) dmg *= (1f - t.s.armor);
        for (Unit a : t.owner.auras)
            if (!a.dead && a != t && a.dist(t) < a.s.aura) {
                dmg *= 0.7f;
                break;
            }
        t.lastHit = w.time;
        if (t.shield > 0) {
            float absorbed = Math.min(t.shield, dmg);
            t.shield -= absorbed;
            dmg -= absorbed;
        }
        if (t.hp - dmg <= 0 && lastStand(t)) {
            t.hp = 1f;
            t.lastStand = w.time;
            w.text(t.x, t.y + 14, "Last Stand!", Color.WHITE);
            w.ring(t.x, t.y, 40, Color.WHITE, 0.4f);
        } else {
            t.hp -= dmg;
            if (t.hp <= 0) t.kill(false);
        }

        Faction f = t.owner;
        if (f.isMajor()) {
            f.lastAttackedTime = w.time;
            f.lastAttackX = t.x;
            f.lastAttackY = t.y;
            if (f == w.player && w.time - f.lastAlertTime > 20f) {
                f.lastAlertTime = w.time;
                w.notify(f, t.s.name + " is under attack!", World.ALERT);
                w.alert(t.x, t.y);
            }
        }
        // retaliate if idle
        if (src != null && !src.dead && t.armed() && t.target == null && w.canSee(t.owner, src)) t.target = src;
    }

    /** Infantry with the Last Stand research shrug off one killing blow every 30 s. */
    private boolean lastStand(Unit t) {
        return (t.cls == ShipClass.LANCER || t.cls == ShipClass.DRONE) && t.owner.has(Tech.LAST_STAND)
                && w.time - t.lastStand >= 30f;
    }

    public void nova(Unit u, float x, float y) {
        w.hash.query(x, y, 380, tmp);
        for (Unit c : tmp)
            if (!c.dead && c.owner != u.owner && c.dist(x, y) - c.radius < 300) applyDamage(c, 1500f * u.owner.dmgMult(), true, u);
        w.beam(u.x, u.y, x, y, NOVA, 8f, 0.5f);
        w.blast(x, y, 300, NOVA);
        w.ring(x, y, 600, NOVA, 1f);
    }

    /** Respawns native guards while a planet's turrets stand. */
    public void updateNatives(float dt) {
        for (Body b : w.bodies) {
            if (b.dead || b.turrets.isEmpty()) continue;
            b.guards.removeIf(g -> g.dead);
            if (!b.guarded()) continue;
            if (b.guards.size() < b.maxGuards) {
                b.guardTimer -= dt;
                if (b.guardTimer <= 0) {
                    b.guardTimer = 30f;
                    double a = w.rng.nextDouble() * Math.PI * 2;
                    Unit g = w.spawn(ShipClass.GUARD, w.natives, b.x + (float) Math.cos(a) * (b.radius + 30), b.y + (float) Math.sin(a) * (b.radius + 30));
                    g.home = b;
                    b.guards.add(g);
                }
            }
        }
    }
}

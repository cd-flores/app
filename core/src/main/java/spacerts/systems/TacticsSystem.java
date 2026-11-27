package spacerts.systems;

import com.badlogic.gdx.graphics.Color;
import spacerts.units.Ability;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Tactic;
import spacerts.units.TacticUpgrade;
import spacerts.units.Unit;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Battle tactics (Command Center): a tactical attack first forms the selected ships into the tactic's formation,
 * marches them together at the slowest ship's speed, then fights with the tactic's behaviour. Runs every tick
 * before unit behaviour, from inside the simulation, so it is deterministic.
 */
public class TacticsSystem {
    public enum Role { TANK, INFANTRY, SUPPORT, SNIPER }

    public enum State { FORMING, MARCHING, ENGAGED }

    /** Formations finish forming after this long even if a ship is stuck. */
    public static final float FORM_TIMEOUT = 10f;
    /** Engage when a visible enemy is this close to the formation. */
    public static final float ENGAGE_RANGE = 700f;
    /** Pincer wings close in once a tank drops below this share of its hull. */
    public static final float PINCER_TRIGGER = 0.85f;
    /** Flank: the rear group's arc ends this far ahead of the tanks. */
    public static final float FLANK_REACH = 900f;
    /** Focus snipers: bonus damage on tanks and Hives. */
    public static final float FOCUS_BONUS = 1.5f;
    /** Ramming Plates: damage per bump (armor applies), once a second per victim. */
    public static final float RAM_DAMAGE = 800f * Unit.DURABILITY;
    /** Shield Wall: sniper damage taken by Bulldozer tanks, and their Taunt radius. */
    public static final float SHIELD_WALL_TAKEN = 0.6f, SHIELD_WALL_TAUNT = 1000f;
    /** Crippling Line: speed while crippled, and for how long. */
    public static final float CRIPPLE_MULT = 0.95f, CRIPPLE_TIME = 3f;
    /** Martyr Charge: reach beyond the infantry's own range, charge time, stun radius and length. */
    public static final float MARTYR_REACH = 100f, MARTYR_TIME = 2f, MARTYR_RADIUS = 50f, MARTYR_STUN = 0.5f;

    public static final class Member {
        public final Unit u;
        public final Role role;
        /** Slot in formation coordinates: x toward the target, y across (positive = left). */
        public float sx, sy;
        /** Flank: 0 front, 1 rear-left, 2 rear-right. Pincer: 0, 1 left wing, 2 right wing. */
        public int group;
        public int waypoint;

        Member(Unit u, Role role) {
            this.u = u;
            this.role = role;
        }
    }

    public static final class Formation {
        public final int id;
        public final Faction f;
        public final Tactic tactic;
        public final List<Member> members = new ArrayList<>();
        public float ax, ay, hx, hy;
        public final float tx, ty;
        public State state = State.FORMING;
        float timer, pickTimer, startTime;
        /** Pincer: wings released. */
        boolean triggered;
        /** Flank: arc frame frozen when the fight starts (tank centroid and heading). */
        float fx, fy, fhx, fhy;

        Formation(int id, Faction f, Tactic tactic, float tx, float ty) {
            this.id = id;
            this.f = f;
            this.tactic = tactic;
            this.tx = tx;
            this.ty = ty;
        }

        /** Slot of m in world coordinates. */
        public float slotX(Member m) { return ax + hx * m.sx - hy * m.sy; }
        public float slotY(Member m) { return ay + hy * m.sx + hx * m.sy; }
    }

    private final World w;
    public final List<Formation> formations = new ArrayList<>();
    private final Map<Integer, Formation> byId = new HashMap<>();
    private int nextId = 1;
    private final List<Unit> tmp = new ArrayList<>();

    public TacticsSystem(World w) {
        this.w = w;
    }

    // ------------------------------------------------------------------ roles

    /** Formation role of a ship class, or null for ships that never join a formation. */
    public static Role role(ShipClass c) {
        switch (c) {
            case BULWARK: case AEGIS: case SOVEREIGN: case LEVIATHAN: return Role.TANK;
            case LANCER: case CORSAIR: case PYRE: case PATROL: case PATROL_SKIFF: case BROODLING: case WISP: return Role.INFANTRY;
            case HIVE: case HALCYON: return Role.SUPPORT;
            case LONGBOW: case PHANTOM: case TEMPEST: return Role.SNIPER;
            default: return null;
        }
    }

    /** Whether the tactic has a place for this role (Focus has no support line). */
    public static boolean uses(Tactic t, Role r) {
        return r != null && !(t == Tactic.FOCUS && r == Role.SUPPORT);
    }

    public Formation formationOf(Unit u) {
        return u.formation == 0 ? null : byId.get(u.formation);
    }

    /** The tactic u is fighting under right now (only once its formation is engaged), or null. */
    public Tactic engagedTactic(Unit u) {
        Formation fm = formationOf(u);
        return fm != null && fm.state == State.ENGAGED ? fm.tactic : null;
    }

    /** The tactic u's formation uses (any state), or null. */
    public Tactic tacticOf(Unit u) {
        Formation fm = formationOf(u);
        return fm != null ? fm.tactic : null;
    }

    // ------------------------------------------------------------------ start

    /** Forms the given ships (all with a role the tactic uses) into a new formation attacking (tx, ty). */
    public Formation start(Faction f, Tactic tactic, List<Unit> ships, float tx, float ty) {
        Formation fm = new Formation(nextId++, f, tactic, tx, ty);
        fm.startTime = w.time;
        float cx = 0, cy = 0;
        for (Unit u : ships) {
            Formation old = formationOf(u);
            if (old != null) old.members.removeIf(m -> m.u == u);
            u.formation = fm.id;
            u.clearAuto();
            u.fleeTimer = 0;
            fm.members.add(new Member(u, role(u.cls)));
            cx += u.x;
            cy += u.y;
        }
        fm.ax = cx / ships.size();
        fm.ay = cy / ships.size();
        float dx = tx - fm.ax, dy = ty - fm.ay, d = (float) Math.hypot(dx, dy);
        fm.hx = d > 1 ? dx / d : 1;
        fm.hy = d > 1 ? dy / d : 0;
        layout(fm);
        formations.add(fm);
        byId.put(fm.id, fm);
        return fm;
    }

    private static List<Member> of(Formation fm, Role r) {
        List<Member> out = new ArrayList<>();
        for (Member m : fm.members) if (m.role == r) out.add(m);
        out.sort((a, b) -> Integer.compare(a.u.id, b.u.id));
        return out;
    }

    private static float spacing(List<Member> ms) {
        float r = 0;
        for (Member m : ms) r = Math.max(r, m.u.radius);
        return Math.max(60f, 2.6f * r);
    }

    /** A line across the formation at forward position x, centred on y0. */
    private static void line(List<Member> ms, float x, float y0, float s) {
        for (int i = 0; i < ms.size(); i++) {
            ms.get(i).sx = x;
            ms.get(i).sy = y0 + (i - (ms.size() - 1) / 2f) * s;
        }
    }

    /** Rows of up to perRow, the first row at x and the others behind it. Returns the depth. */
    private static float block(List<Member> ms, float x, float y0, float s, int perRow) {
        int rows = (ms.size() + perRow - 1) / perRow;
        for (int i = 0; i < ms.size(); i++) {
            int row = i / perRow, col = i % perRow;
            int inRow = Math.min(perRow, ms.size() - row * perRow);
            ms.get(i).sx = x - row * s;
            ms.get(i).sy = y0 + (col - (inRow - 1) / 2f) * s;
        }
        return Math.max(0, rows - 1) * s;
    }

    private static float halfWidth(List<Member> ms, float s) {
        return Math.max(0, ms.size() - 1) / 2f * s;
    }

    private void layout(Formation fm) {
        List<Member> tanks = of(fm, Role.TANK), inf = of(fm, Role.INFANTRY), sup = of(fm, Role.SUPPORT), snip = of(fm, Role.SNIPER);
        float st = spacing(tanks), si = spacing(inf), ss = spacing(sup), sn = spacing(snip);
        switch (fm.tactic) {
            case BULLDOZER: {
                // tanks in a block, infantry in a ring around it, support behind the ring, snipers at the back
                float depth = block(tanks, 0, 0, st, 3);
                float cx = -depth / 2f;
                float hw = tanks.isEmpty() ? 0 : (Math.min(3, tanks.size()) - 1) / 2f * st;
                float br = (float) Math.hypot(hw, depth / 2f) + (tanks.isEmpty() ? 0 : st / 2.6f);
                float ring = Math.max(br + 70f, inf.size() * si / (float) (2 * Math.PI));
                for (int i = 0; i < inf.size(); i++) {
                    double a = Math.PI * 2 * i / Math.max(1, inf.size());
                    inf.get(i).sx = cx + (float) Math.cos(a) * ring;
                    inf.get(i).sy = (float) Math.sin(a) * ring;
                }
                float back = cx - (inf.isEmpty() ? br : ring);
                line(sup, back - ss, 0, ss);
                line(snip, back - ss - Math.max(sn, 120f), 0, sn);
                break;
            }
            case PINCER: {
                line(tanks, 0, 0, st);
                float wing = halfWidth(tanks, st) + 120f + si;
                int half = (inf.size() + 1) / 2;
                List<Member> left = new ArrayList<>(inf.subList(0, half)), right = new ArrayList<>(inf.subList(half, inf.size()));
                block(left, -80f, wing, si, 2);
                block(right, -80f, -wing, si, 2);
                for (Member m : left) m.group = 1;
                for (Member m : right) m.group = 2;
                line(sup, -st - 60f, 0, ss);
                line(snip, -st - 60f - Math.max(sn, 150f), 0, sn);
                break;
            }
            case FLANK: {
                int hi = (inf.size() + 1) / 2, hs = (snip.size() + 1) / 2;
                List<Member> fInf = new ArrayList<>(inf.subList(0, hi)), rInf = new ArrayList<>(inf.subList(hi, inf.size()));
                List<Member> fSn = new ArrayList<>(snip.subList(0, hs)), rSn = new ArrayList<>(snip.subList(hs, snip.size()));
                line(tanks, 0, 0, st);
                line(fInf, -si * 0.9f, 0, si);
                float frontBack = -si * 0.9f - Math.max(sn, 110f);
                line(fSn, frontBack, 0, sn);
                float rearX = frontBack - 300f; // the gap between the halves
                line(rInf, rearX, 0, si);
                line(rSn, rearX - Math.max(sn, 110f), 0, sn);
                line(sup, frontBack + 40f, 0, ss); // Hives and Halcyons stay with the front group
                for (Member m : rInf) m.group = m.sy >= 0 ? 1 : 2;
                for (Member m : rSn) m.group = m.sy >= 0 ? 1 : 2;
                break;
            }
            case FOCUS:
            default: {
                line(inf, 0, 0, si);
                line(tanks, -Math.max(si, st), 0, st);
                line(snip, -Math.max(si, st) - Math.max(sn, 130f), 0, sn);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ update

    public void update(float dt) {
        for (int i = formations.size() - 1; i >= 0; i--) {
            Formation fm = formations.get(i);
            prune(fm);
            if (fm.members.size() < 2 || fm.f.eliminated) {
                end(fm);
                continue;
            }
            fm.timer += dt;
            switch (fm.state) {
                case FORMING: forming(fm); break;
                case MARCHING: marching(fm, dt); break;
                default: engaged(fm, dt); break;
            }
        }
    }

    /** Drops members that died, were given other orders, or pulled out to repair. Dead ones may be replenished. */
    private void prune(Formation fm) {
        for (int i = fm.members.size() - 1; i >= 0; i--) {
            Member m = fm.members.get(i);
            Unit u = m.u;
            if (u.dead) {
                fm.members.remove(i);
                if (!u.silent) replenish(fm, u.cls);
            } else if (u.formation != fm.id || u.retreating) {
                if (u.formation == fm.id) u.formation = 0;
                fm.members.remove(i);
            }
        }
    }

    private void end(Formation fm) {
        for (Member m : fm.members) {
            if (m.u.formation != fm.id) continue;
            m.u.formation = 0;
            if (m.u.martyrTarget == null && m.u.order != Order.ATTACK_MOVE) m.u.idle();
        }
        formations.remove(fm);
        byId.remove(fm.id);
    }

    private void forming(Formation fm) {
        boolean all = true;
        for (Member m : fm.members) {
            if (m.u.martyrTarget != null) continue;
            float x = fm.slotX(m), y = fm.slotY(m);
            moveTo(m.u, x, y);
            if (m.u.dist(x, y) > 50f) all = false;
        }
        if (all || fm.timer > FORM_TIMEOUT) {
            fm.state = State.MARCHING;
            fm.timer = 0;
        }
    }

    private void marching(Formation fm, float dt) {
        float speed = Float.MAX_VALUE;
        boolean lagging = false;
        for (Member m : fm.members) {
            speed = Math.min(speed, m.u.speed());
            if (m.u.dist(fm.slotX(m), fm.slotY(m)) > 250f) lagging = true;
        }
        float dx = fm.tx - fm.ax, dy = fm.ty - fm.ay, d = (float) Math.hypot(dx, dy);
        if (!lagging && d > 1f) { // the anchor waits for stragglers so the shape holds
            float step = Math.min(d, speed * 0.9f * dt);
            fm.ax += dx / d * step;
            fm.ay += dy / d * step;
        }
        for (Member m : fm.members) if (m.u.martyrTarget == null) moveTo(m.u, fm.slotX(m), fm.slotY(m));
        boolean hit = false;
        for (Member m : fm.members) if (m.u.lastHit > fm.startTime && w.time - m.u.lastHit < 1f) hit = true;
        if (hit || d < 30f || nearestEnemy(fm.f, fm.ax, fm.ay, ENGAGE_RANGE + extent(fm)) != null) {
            fm.state = State.ENGAGED;
            fm.timer = 0;
            float[] tc = tankCentroid(fm);
            fm.fx = tc[0];
            fm.fy = tc[1];
            fm.fhx = fm.hx;
            fm.fhy = fm.hy;
        }
    }

    // ------------------------------------------------------------------ engaged behaviour

    private void engaged(Formation fm, float dt) {
        float[] ec = enemyCentre(fm);
        float[] tc = tankCentroid(fm);
        fm.pickTimer -= dt;
        boolean pick = fm.pickTimer <= 0;
        if (pick) fm.pickTimer = 0.25f;
        // the formation's frame follows the tanks toward the enemy
        float dx = ec[0] - tc[0], dy = ec[1] - tc[1], d = (float) Math.hypot(dx, dy);
        if (d > 1) {
            fm.hx = dx / d;
            fm.hy = dy / d;
        }
        switch (fm.tactic) {
            case BULLDOZER: bulldozer(fm, ec, tc, pick); break;
            case PINCER: pincer(fm, ec, tc, pick); break;
            case FLANK: flank(fm, ec, tc, pick); break;
            default: focus(fm, ec, pick); break;
        }
        // done: nothing left nearby and the destination reached
        float[] c = centroid(fm);
        if (nearestEnemy(fm.f, c[0], c[1], 1500f) == null && Math.hypot(c[0] - fm.tx, c[1] - fm.ty) < 400f) end(fm);
        else if (nearestEnemy(fm.f, c[0], c[1], 1500f) == null) {
            for (Member m : fm.members) {
                boolean arcing = fm.tactic == Tactic.FLANK && m.group != 0 && m.waypoint < FLANK_WAYPOINTS; // let the swing finish
                if (!arcing && m.u.martyrTarget == null && m.u.order != Order.ATTACK_MOVE) attackMove(m.u, fm.tx, fm.ty);
            }
        }
    }

    private void bulldozer(Formation fm, float[] ec, float[] tc, boolean pick) {
        // anchor on the tanks: the block centre sits where the tanks are
        fm.ax = tc[0];
        fm.ay = tc[1];
        boolean ram = fm.f.has(TacticUpgrade.RAM);
        Unit closeToTanks = pick ? nearestEnemy(fm.f, tc[0], tc[1], 1500f) : null;
        for (Member m : fm.members) {
            Unit u = m.u;
            if (u.martyrTarget != null) continue;
            switch (m.role) {
                case TANK:
                    if (ram) { // creep straight into the enemy and run them over
                        Unit e = nearestEnemy(fm.f, u.x, u.y, 900f);
                        if (e != null) {
                            float ex = e.x - u.x, ey = e.y - u.y, el = Math.max(1f, (float) Math.hypot(ex, ey));
                            moveTo(u, e.x + ex / el * 150f, e.y + ey / el * 150f);
                            ram(u);
                        } else attackMove(u, ec[0], ec[1]);
                    } else attackMove(u, ec[0], ec[1]);
                    break;
                case INFANTRY: {
                    float x = fm.slotX(m), y = fm.slotY(m);
                    if (u.dist(x, y) > 250f) moveTo(u, x, y); // keep the ring around the tanks
                    else attackMove(u, ec[0], ec[1]);
                    break;
                }
                case SUPPORT:
                    moveTo(u, fm.slotX(m), fm.slotY(m));
                    break;
                case SNIPER:
                    if (pick) {
                        if (closeToTanks != null) attack(u, closeToTanks);
                        else attackMove(u, ec[0], ec[1]);
                    }
                    break;
            }
        }
    }

    private void pincer(Formation fm, float[] ec, float[] tc, boolean pick) {
        if (!fm.triggered)
            for (Member m : fm.members)
                if (m.role == Role.TANK && m.u.hp < m.u.maxHp() * PINCER_TRIGGER) fm.triggered = true;
        fm.ax = tc[0];
        fm.ay = tc[1];
        for (Member m : fm.members) {
            Unit u = m.u;
            if (u.martyrTarget != null) continue;
            switch (m.role) {
                case TANK: attackMove(u, ec[0], ec[1]); break;
                case SUPPORT: moveTo(u, tc[0] - fm.hx * 160f, tc[1] - fm.hy * 160f); break;
                case INFANTRY:
                    if (!fm.triggered) {
                        moveTo(u, fm.slotX(m), fm.slotY(m)); // wings hold their sides
                    } else if (m.waypoint == 0) { // close in around the enemy's sides
                        float side = m.group == 1 ? 1 : -1;
                        float px = ec[0] - fm.hy * 450f * side + fm.hx * 250f, py = ec[1] + fm.hx * 450f * side + fm.hy * 250f;
                        moveTo(u, px, py);
                        if (u.dist(px, py) < 120f) m.waypoint = 1;
                    } else attackMove(u, ec[0], ec[1]);
                    break;
                case SNIPER:
                    if (pick) { // 3 shots at the nearest, 3 at the farthest, and so on
                        Unit t = pincerPick(u);
                        if (t != null && t != u.target) attack(u, t);
                        else if (t == null) attackMove(u, ec[0], ec[1]);
                    }
                    break;
            }
        }
    }

    private void flank(Formation fm, float[] ec, float[] tc, boolean pick) {
        for (Member m : fm.members) {
            Unit u = m.u;
            if (u.martyrTarget != null) continue;
            if (m.role == Role.TANK) {
                attackMove(u, ec[0], ec[1]);
                // once hurt, taunt now and again every time it recharges
                if (u.lastHit > fm.startTime && u.s.has(Ability.TAUNT) && u.abilityCds[Ability.TAUNT.ordinal()] <= 0)
                    w.behavior.useAbility(u, Ability.TAUNT);
                continue;
            }
            if (m.group == 0) { // the front half fights with the tanks
                if (m.role == Role.SUPPORT) moveTo(u, tc[0] - fm.hx * 160f, tc[1] - fm.hy * 160f);
                else attackMove(u, ec[0], ec[1]);
                continue;
            }
            // the rear half swings round in a half circle, left or right, to land 900 past the tanks
            if (m.waypoint < FLANK_WAYPOINTS) {
                float[] p = flankWaypoint(fm, m, m.waypoint + 1);
                moveTo(u, p[0], p[1]);
                if (u.dist(p[0], p[1]) < 120f) m.waypoint++;
            } else attackMove(u, ec[0], ec[1]);
        }
    }

    /** Waypoints on the Flank arc. */
    public static final int FLANK_WAYPOINTS = 4;

    /** Waypoint k (1..4) of the rear group's arc, in the frame frozen when the fight began. */
    private float[] flankWaypoint(Formation fm, Member m, int k) {
        float side = m.group == 1 ? 1 : -1;
        float start = m.sx - 0f; // rear slot, behind the tanks
        float x0 = (start + FLANK_REACH) / 2f, rx = (FLANK_REACH - start) / 2f, ry = 600f;
        double th = Math.PI * (1 - k / (double) FLANK_WAYPOINTS);
        float lx = x0 + rx * (float) Math.cos(th), ly = side * (ry * (float) Math.sin(th) + 200f);
        return new float[]{fm.fx + fm.fhx * lx - fm.fhy * ly, fm.fy + fm.fhy * lx + fm.fhx * ly};
    }

    private void focus(Formation fm, float[] ec, boolean pick) {
        for (Member m : fm.members) {
            Unit u = m.u;
            if (u.martyrTarget != null) continue;
            if (m.role != Role.SNIPER) {
                attackMove(u, ec[0], ec[1]);
                continue;
            }
            if (!pick) continue;
            // hive drones first, then whatever has the most hull
            Unit t = focusTarget(u);
            if (t != null) {
                if (t != u.target) attack(u, t);
            } else attackMove(u, ec[0], ec[1]);
        }
    }

    /** Focus snipers: hive drones first, then the enemy with the most hull. */
    public Unit focusTarget(Unit u) {
        float reach = u.range() + 300f;
        w.hash.query(u.x, u.y, reach + 80f, tmp);
        Unit drone = null, big = null;
        float dd = Float.MAX_VALUE, bh = -1;
        for (Unit c : tmp) {
            if (c.dead || !u.owner.hostileTo(c.owner) || !w.canSee(u.owner, c)) continue;
            float d = u.dist(c) - c.radius;
            if (d > reach) continue;
            if (c.cls == ShipClass.DRONE) {
                if (d < dd) { dd = d; drone = c; }
            } else if (c.hp > bh) {
                bh = c.hp;
                big = c;
            }
        }
        return drone != null ? drone : big;
    }

    // ------------------------------------------------------------------ upgrades

    /** Ramming Plates: a Bulldozer tank deals heavy damage to enemy ships its hull touches (once a second each). */
    private void ram(Unit tank) {
        w.hash.query(tank.x, tank.y, tank.radius + 90f, tmp);
        for (Unit e : tmp) {
            if (e.dead || !tank.owner.hostileTo(e.owner) || e.cls.isBuilding() || e.cls == ShipClass.DRONE) continue;
            if (tank.dist(e) > tank.radius + e.radius + 4f || w.time - e.lastRammed < 1f) continue;
            e.lastRammed = w.time;
            w.combat.applyDamage(e, RAM_DAMAGE, false, tank);
            w.ring(e.x, e.y, e.radius * 2f, new Color(1f, 0.7f, 0.3f, 1f), 0.35f);
        }
    }

    /** Replenish: a formation ship that died is re-queued at the owner's least-busy Ship Station, at normal price. */
    private void replenish(Formation fm, ShipClass c) {
        Faction f = fm.f;
        if (!f.has(TacticUpgrade.REPLENISH) || !c.isWarship()) return;
        if (++f.replenishLost[c.ordinal()] < c.s.squad) return; // wait until a whole squad's worth is gone
        f.replenishLost[c.ordinal()] -= c.s.squad;
        Unit best = null;
        for (Unit p : w.units)
            if (!p.dead && p.owner == f && p.cls == ShipClass.FACTORY && w.production.blocker(p, c) == null
                    && (best == null || p.queue.items.size() < best.queue.items.size())) best = p;
        if (best == null) {
            w.notify(f, "Replenish: no Ship Station can build a new " + c.s.name + ".", World.INFO);
            return;
        }
        String err = w.production.enqueue(best, c);
        if (err != null) {
            w.notify(f, "Replenish skipped " + c.s.name + ": " + err + ".", World.INFO);
            return;
        }
        f.replenish[c.ordinal()]++;
    }

    /**
     * Martyr Charge: a Flank infantry ship about to die with an enemy sniper within its range + 100 charges that
     * sniper instead. Returns true when the killing blow was turned into a charge.
     */
    public boolean martyr(Unit t) {
        if (t.martyrTarget != null) return true;
        if (!t.owner.has(TacticUpgrade.MARTYR) || tacticOf(t) != Tactic.FLANK || role(t.cls) != Role.INFANTRY) return false;
        float reach = t.range() + MARTYR_REACH;
        w.hash.query(t.x, t.y, reach + 80f, tmp);
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for (Unit c : tmp) {
            if (c.dead || !t.owner.hostileTo(c.owner) || role(c.cls) != Role.SNIPER || !w.canSee(t.owner, c)) continue;
            float d = t.dist(c) - c.radius;
            if (d <= reach && d < bd) { bd = d; best = c; }
        }
        if (best == null) return false;
        t.hp = 1f;
        t.martyrTarget = best;
        t.martyrTimer = MARTYR_TIME;
        t.target = null;
        w.text(t.x, t.y + 14, "Martyr!", Color.ORANGE);
        return true;
    }

    /** Called each tick for a charging martyr: steer into the sniper, explode on contact or when time runs out. */
    public void martyrUpdate(Unit u, float dt) {
        Unit s = u.martyrTarget;
        u.martyrTimer -= dt;
        boolean gone = s == null || s.dead;
        if (!gone) {
            u.hasGoal = true;
            u.gx = s.x;
            u.gy = s.y;
            u.stopDist = 0;
        }
        if (gone || u.martyrTimer <= 0 || (s != null && u.dist(s) <= u.radius + s.radius + 8f)) {
            float x = gone ? u.x : s.x, y = gone ? u.y : s.y;
            w.hash.query(x, y, MARTYR_RADIUS + 80f, tmp);
            for (Unit e : tmp) {
                if (e.dead || !u.owner.hostileTo(e.owner)) continue;
                if (e == s || e.dist(x, y) - e.radius <= MARTYR_RADIUS) e.stunTimer = Math.max(e.stunTimer, MARTYR_STUN);
            }
            w.blast(x, y, 60f, u.owner.color);
            w.ring(x, y, MARTYR_RADIUS * 2f, Color.WHITE, 0.4f);
            u.martyrTarget = null;
            u.kill(false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void moveTo(Unit u, float x, float y) {
        if (u.order == Order.MOVE && Math.abs(u.tx - x) < 4 && Math.abs(u.ty - y) < 4) return;
        u.order = Order.MOVE;
        u.tx = x;
        u.ty = y;
        u.body = null;
    }

    private static void attackMove(Unit u, float x, float y) {
        if (u.order == Order.ATTACK_MOVE && Math.abs(u.tx - x) < 60 && Math.abs(u.ty - y) < 60) return;
        u.order = Order.ATTACK_MOVE;
        u.tx = x;
        u.ty = y;
        u.anchorX = x;
        u.anchorY = y;
        u.body = null;
    }

    private static void attack(Unit u, Unit t) {
        u.order = Order.ATTACK;
        u.target = t;
        u.body = null;
    }

    /** Nearest visible hostile within r of (x, y), or null. */
    public Unit nearestEnemy(Faction f, float x, float y, float r) {
        w.hash.query(x, y, r + 80f, tmp);
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for (Unit c : tmp) {
            if (c.dead || !f.hostileTo(c.owner) || !w.canSee(f, c)) continue;
            float d = c.dist(x, y) - c.radius;
            if (d <= r && d < bd) { bd = d; best = c; }
        }
        return best;
    }

    /** Pincer snipers: 3 shots at the nearest enemy, then 3 at the farthest in range, and so on. */
    public Unit pincerPick(Unit u) {
        boolean far = (u.shotCount / 3) % 2 == 1;
        Unit t = far ? farthestInRange(u) : nearestEnemy(u.owner, u.x, u.y, u.range() + 50f);
        return t != null ? t : nearestEnemy(u.owner, u.x, u.y, 1500f);
    }

    private Unit farthestInRange(Unit u) {
        w.hash.query(u.x, u.y, u.range() + 80f, tmp);
        Unit best = null;
        float bd = -1;
        for (Unit c : tmp) {
            if (c.dead || !u.owner.hostileTo(c.owner) || !w.canSee(u.owner, c)) continue;
            float d = u.dist(c) - c.radius;
            if (d <= u.range() && d >= u.s.minRange && d > bd) { bd = d; best = c; }
        }
        return best;
    }

    private float[] centroid(Formation fm) {
        float x = 0, y = 0;
        for (Member m : fm.members) {
            x += m.u.x;
            y += m.u.y;
        }
        int n = Math.max(1, fm.members.size());
        return new float[]{x / n, y / n};
    }

    /** Centroid of the tanks (the whole formation when there are none). */
    private float[] tankCentroid(Formation fm) {
        float x = 0, y = 0;
        int n = 0;
        for (Member m : fm.members)
            if (m.role == Role.TANK) {
                x += m.u.x;
                y += m.u.y;
                n++;
            }
        return n == 0 ? centroid(fm) : new float[]{x / n, y / n};
    }

    /** Largest distance from the anchor to a slot. */
    private float extent(Formation fm) {
        float e = 0;
        for (Member m : fm.members) e = Math.max(e, (float) Math.hypot(m.sx, m.sy));
        return e;
    }

    /** Centroid of the visible enemies within 1200 of the formation, else the target point. */
    private float[] enemyCentre(Formation fm) {
        float[] c = centroid(fm);
        w.hash.query(c[0], c[1], 1200f + 80f, tmp);
        float x = 0, y = 0;
        int n = 0;
        for (Unit e : tmp) {
            if (e.dead || !fm.f.hostileTo(e.owner) || !w.canSee(fm.f, e) || e.dist(c[0], c[1]) > 1200f) continue;
            x += e.x;
            y += e.y;
            n++;
        }
        return n == 0 ? new float[]{fm.tx, fm.ty} : new float[]{x / n, y / n};
    }
}

package spacerts.systems;

import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/** Endless escalation: pirate raids that grow with time and empire size. */
public class ThreatDirector {
    private final World w;
    public float nextWave = 300f;
    public int wave;
    private float manageTimer;

    public ThreatDirector(World w) {
        this.w = w;
    }

    /** Pirate wave strength: grows with wave number, game time and the victim's empire, scaled by difficulty. */
    public float budget(int wave, float time, int empire) {
        return (3f + wave * 1.1f + time / 150f + empire * 0.6f) * w.difficulty.pirateBudget;
    }

    public void update(float dt) {
        // pirates slowly get tougher forever
        w.pirates.extraHp = w.time / 1200f * 0.25f;
        w.pirates.extraDmg = w.time / 1200f * 0.2f;

        if (w.time >= nextWave) launch();

        manageTimer -= dt;
        if (manageTimer <= 0) {
            manageTimer = 2f;
            managePirates();
        }
    }

    private void launch() {
        wave++;
        float interval = Math.max(75f, 240f - w.time / 15f);
        nextWave = w.time + interval * (0.85f + w.rng.nextFloat() * 0.3f);

        // choose victim faction (player weighted double)
        List<Faction> majors = new ArrayList<>();
        for (Faction f : w.factions)
            if (f.isMajor() && !f.eliminated) {
                majors.add(f);
                if (f.isPlayer()) majors.add(f);
            }
        if (majors.isEmpty()) return;
        Faction victim = majors.get(w.rng.nextInt(majors.size()));
        Unit target = pickTarget(victim, w.rng.nextFloat() < 0.5f);
        if (target == null) return;

        int empire = 0;
        for (Unit u : w.units) if (!u.dead && u.owner == victim && (u.cls == ShipClass.COLONY || u.cls == ShipClass.OUTPOST)) empire++;
        float budget = budget(wave, w.time, empire);

        // spawn point beyond the target, away from the map centre, ~2600 away
        float cx = World.SIZE / 2, cy = World.SIZE / 2;
        float dx = target.x - cx, dy = target.y - cy;
        float len = Math.max(1f, (float) Math.hypot(dx, dy));
        float sx = World.clampPos(target.x + dx / len * 2600), sy = World.clampPos(target.y + dy / len * 2600);
        if (Math.hypot(sx - target.x, sy - target.y) < 2000) {
            float a = (float) Math.atan2(dy, dx) + (float) Math.PI;
            sx = World.clampPos(target.x + (float) Math.cos(a + 0.8f) * 2600);
            sy = World.clampPos(target.y + (float) Math.sin(a + 0.8f) * 2600);
        }

        int count = 0;
        boolean dread = w.time > 1500 && wave % 6 == 0;
        while (budget > 0 && count < 60) {
            ShipClass c;
            if (dread) { c = ShipClass.DREADNOUGHT; budget -= 25; dread = false; }
            else if (w.time > 600 && w.rng.nextFloat() < 0.35f) { c = ShipClass.GUNSHIP; budget -= 4; }
            else { c = ShipClass.RAIDER; budget -= 1; }
            Unit p = w.spawn(c, w.pirates, sx + w.rng.nextFloat() * 300 - 150, sy + w.rng.nextFloat() * 300 - 150);
            p.command(Order.ATTACK_MOVE, target.x + w.rng.nextFloat() * 100 - 50, target.y + w.rng.nextFloat() * 100 - 50);
            count++;
        }
        if (victim == w.player) {
            w.notify(victim, "Pirate raid detected! " + count + " ships inbound to your " + target.s.name + ".", World.ALERT);
            w.alert(target.x, target.y);
        }
    }

    /** Weakest structure (or harvester) of a faction, by nearby defence and distance from its mothership. */
    private Unit pickTarget(Faction f, boolean preferWeak) {
        Unit ms = f.mothership;
        Unit best = null;
        float bestScore = Float.MAX_VALUE;
        for (Unit u : w.units) {
            if (u.dead || u.owner != f) continue;
            boolean candidate = u.cls == ShipClass.OUTPOST || u.cls == ShipClass.COLONY || u.cls == ShipClass.SHIPYARD || u.cls == ShipClass.FACTORY
                    || u.cls == ShipClass.SOLAR || u.cls == ShipClass.MINER
                    || u.cls == ShipClass.HUB || u.s.harvester;
            if (!candidate) continue;
            float defence = 0;
            for (Unit o : w.units)
                if (!o.dead && o.owner == f && o.armed() && o.dist(u) < 900) defence += o.power();
            float score = defence * 10 - (ms != null ? ms.dist(u) * 0.01f : 0) + (preferWeak ? 0 : w.rng.nextFloat() * 30);
            if (score < bestScore) {
                bestScore = score;
                best = u;
            }
        }
        return best != null ? best : (ms != null && !ms.dead ? ms : null);
    }

    private void managePirates() {
        for (Unit p : w.units) {
            if (p.dead || p.owner != w.pirates) continue;
            if (p.leaving) {
                if (p.order == Order.IDLE || p.dist(p.tx, p.ty) < 80) p.kill(true);
                continue;
            }
            if (p.order != Order.IDLE || p.target != null) continue;
            // find the next structure or harvester nearby
            Unit next = null;
            float bd = 3500;
            for (Unit u : w.units) {
                if (u.dead || !u.owner.isMajor()) continue;
                if (!(u.cls.isBuilding() || u.s.harvester)) continue;
                float d = p.dist(u);
                if (d < bd) { bd = d; next = u; }
            }
            if (next != null) p.command(Order.ATTACK_MOVE, next.x, next.y);
            else {
                float ex = p.x < World.SIZE / 2 ? 40 : World.SIZE - 40;
                p.command(Order.MOVE, ex, p.y);
                p.leaving = true;
            }
        }
    }
}
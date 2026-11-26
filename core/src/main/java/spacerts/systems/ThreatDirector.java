package spacerts.systems;

import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Faction;
import spacerts.world.PirateBases;
import spacerts.world.StarSystem;
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

    /** Hive: pirate raid strength from how far up the hive the player holds hexes, not from the clock. */
    public float hiveBudget(int wave, int frontier, int ownedHexes) {
        return (3f + Math.min(wave, 30) * 0.5f + frontier * 1.6f + ownedHexes * 0.15f) * w.difficulty.pirateBudget;
    }

    private float surgeTimer = SURGE_EVERY;
    public static final float SURGE_EVERY = 60f;

    public void update(float dt) {
        if (w.raidsFrozen) nextWave += dt; // DayLightSavings cheat: the raid timer stands still
        if (w.galaxy.hive) { // pirates harden the higher you climb, so a campaign can run for days
            int frontier = ConquestSystem.frontier(w);
            w.pirates.extraHp = 0.06f * frontier;
            w.pirates.extraDmg = 0.05f * frontier;
        } else { // pirates slowly get tougher forever
            w.pirates.extraHp = w.time / 1200f * 0.25f;
            w.pirates.extraDmg = w.time / 1200f * 0.2f;
        }

        if (w.piratesOnly()) {
            PirateBases.update(w, dt);
            Unit gateJob = ascensionJob();
            if (gateJob != null && (surgeTimer -= dt) <= 0) { // the last stand: everything comes for the Ascension Gate
                surgeTimer = SURGE_EVERY;
                surge(gateJob);
            }
        }
        if (w.time >= nextWave) launch();

        manageTimer -= dt;
        if (manageTimer <= 0) {
            manageTimer = 2f;
            managePirates();
        }
    }

    /** The player's harvester building the Ascension Gate, once construction has started; null otherwise. */
    public Unit ascensionJob() {
        if (w.ascended) return null;
        for (Unit u : w.units)
            if (!u.dead && u.owner == w.player && u.buildCls == ShipClass.ASCENSION && u.work > 0) return u;
        return null;
    }

    /** A surge wave from every remaining stronghold at the Ascension Gate site. */
    private void surge(Unit job) {
        List<Unit> bases = PirateBases.livingBases(w);
        float per = hiveBudget(wave, ConquestSystem.frontier(w), ConquestSystem.ownedCount(w)) * 0.45f;
        int total = 0;
        Unit site = job; // ships attack-move onto the construction site (the harvester sits there)
        for (Unit b : bases) total += spawnRaid(per, b.x, b.y, b.radius + 160, site);
        if (bases.isEmpty()) total += spawnRaid(per * 2, w.systemAt(job.x, job.y).cx, w.systemAt(job.x, job.y).cy - 2500, 0, site);
        w.notify(w.player, "PIRATE SURGE! " + total + " ships are converging on the Ascension Gate.", World.ALERT);
        w.alert(job.buildX, job.buildY);
    }

    private void launch() {
        wave++;
        float interval = w.galaxy.hive ? 240f + w.rng.nextFloat() * 90f : Math.max(75f, 240f - w.time / 15f);
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
        float budget = w.galaxy.hive ? hiveBudget(wave, ConquestSystem.frontier(w), ConquestSystem.ownedCount(w)) : budget(wave, w.time, empire);

        // pirates-only: the raid launches from a stronghold and flies there through the jump gates
        Unit source = null;
        if (w.piratesOnly()) {
            List<Unit> bases = PirateBases.livingBases(w);
            source = PirateBases.raidSource(w, bases, target);
            budget *= source != null ? 0.5f + 0.1f * Math.min(bases.size(), 8) : 0.5f; // every fallen base weakens the raids
            if (w.ascended) budget *= 0.25f; // after victory: just a trickle
        }
        if (source != null) {
            int count = spawnRaid(budget, source.x, source.y, source.radius + 160, target);
            if (victim.isPlayer()) {
                StarSystem from = w.systemAt(source.x, source.y), to = w.systemAt(target.x, target.y);
                int jumps = w.galaxy.jumps(from, to);
                w.notify(victim, "Pirate raid! " + count + " ships launched from " + from.name
                        + (jumps > 0 ? ", " + jumps + " jump" + (jumps > 1 ? "s" : "") + " away," : "")
                        + " heading for your " + target.s.name + " in " + to.name + ".", World.ALERT);
                if (victim == w.player) w.alert(target.x, target.y);
            }
            return;
        }

        // spawn point beyond the target, away from the map centre, ~2600 away
        StarSystem sys = w.systemAt(target.x, target.y);
        float cx = sys.cx, cy = sys.cy;
        float dx = target.x - cx, dy = target.y - cy;
        float len = Math.max(1f, (float) Math.hypot(dx, dy));
        float px = target.x + dx / len * 2600, py = target.y + dy / len * 2600;
        float sx = sys.clampX(px, py), sy = sys.clampY(px, py);
        if (Math.hypot(sx - target.x, sy - target.y) < 2000) {
            float a = (float) Math.atan2(dy, dx) + (float) Math.PI;
            px = target.x + (float) Math.cos(a + 0.8f) * 2600;
            py = target.y + (float) Math.sin(a + 0.8f) * 2600;
            sx = sys.clampX(px, py);
            sy = sys.clampY(px, py);
        }

        int count = spawnRaid(budget, sx, sy, 0, target);
        w.notify(victim, "Pirate raid detected! " + count + " ships inbound to your " + target.s.name + ".", World.ALERT);
        if (victim == w.player) w.alert(target.x, target.y);
    }

    /** Spends a raid budget on ships around (sx, sy) (pushed out by `ring`) and sends them at the target. */
    private int spawnRaid(float budget, float sx, float sy, float ring, Unit target) {
        int count = 0;
        int frontier = ConquestSystem.frontier(w);
        boolean hive = w.galaxy.hive;
        boolean dread = (hive ? frontier >= 5 : w.time > 1500) && wave % 6 == 0;
        boolean gunships = hive ? frontier >= 2 : w.time > 600;
        while (budget > 0 && count < 60) {
            ShipClass c;
            if (dread) { c = ShipClass.DREADNOUGHT; budget -= 25; dread = false; }
            else if (gunships && w.rng.nextFloat() < 0.35f) { c = ShipClass.GUNSHIP; budget -= 4; }
            else { c = ShipClass.RAIDER; budget -= 1; }
            float x = sx + w.rng.nextFloat() * 300 - 150, y = sy + w.rng.nextFloat() * 300 - 150;
            if (ring > 0) {
                double a = w.rng.nextDouble() * Math.PI * 2;
                x = sx + (float) Math.cos(a) * (ring + w.rng.nextFloat() * 150);
                y = sy + (float) Math.sin(a) * (ring + w.rng.nextFloat() * 150);
            }
            Unit p = w.spawn(c, w.pirates, x, y);
            p.command(Order.ATTACK_MOVE, target.x + w.rng.nextFloat() * 100 - 50, target.y + w.rng.nextFloat() * 100 - 50);
            count++;
        }
        return count;
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
                    || u.cls == ShipClass.HUB || u.s.harvester || u.cls == ShipClass.BEACON || u.cls == ShipClass.REFINERY
                    || u.cls == ShipClass.SENSOR || u.cls == ShipClass.MENDER;
            if (!candidate) continue;
            float defence = 0;
            for (Unit o : w.units)
                if (!o.dead && o.owner == f && o.armed() && o.dist(u) < 900) defence += o.power();
            float score = defence * 10 - (ms != null ? ms.dist(u) * 0.01f : 0) + (preferWeak ? 0 : w.rng.nextFloat() * 30);
            if (w.galaxy.hive) score -= w.systemAt(u.x, u.y).danger * 40f; // raids hit the frontier hardest
            if (score < bestScore) {
                bestScore = score;
                best = u;
            }
        }
        return best != null ? best : (ms != null && !ms.dead ? ms : null);
    }

    private void managePirates() {
        for (Unit p : w.units) {
            if (p.dead || p.owner != w.pirates || p.cls == ShipClass.PIRATE_BASE) continue;
            if (p.leaving) {
                if (p.order == Order.IDLE || p.dist(p.tx, p.ty) < 80) p.kill(true);
                continue;
            }
            if (p.dormant) continue; // asleep until an empire's ships come near its hex
            if (PirateBases.manageGarrison(w, p)) continue; // ships on station guard their stronghold
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
            else if (w.piratesOnly() && PirateBases.sendHome(w, p)) continue;
            else {
                StarSystem s = w.systemAt(p.x, p.y);
                float ex = p.x < s.cx ? s.minX() + 40 : s.maxX() - 40;
                p.command(Order.MOVE, s.clampX(ex, p.y), s.clampY(ex, p.y));
                p.leaving = true;
            }
        }
    }
}
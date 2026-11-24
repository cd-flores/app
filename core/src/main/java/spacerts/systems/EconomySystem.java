package spacerts.systems;

import spacerts.research.RareItem;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.GalaxyGenerator;
import spacerts.world.World;

/** Harvesting loop, research outposts, and asteroid field respawning. */
public class EconomySystem {
    private final World w;
    private float asteroidTimer = 45f;

    public EconomySystem(World w) {
        this.w = w;
    }

    public void update(float dt) {
        for (int i = 0, n = w.units.size(); i < n; i++) {
            Unit u = w.units.get(i);
            if (u.dead) continue;
            if (u.cls == ShipClass.OUTPOST) extract(u, dt);
            else if (u.cls == ShipClass.SOLAR) u.owner.energy += solarRate(u) * dt;
            else if (u.cls == ShipClass.MINER) mine(u, dt);
            else if (u.cls == ShipClass.COLONY && u.home != null && u.owner.isMajor()) {
                float rate = colonyIncome(u.home) * incomeMult(u.owner);
                u.owner.energy += rate * dt;
                u.owner.metal += rate * dt;
            }
        }
        asteroidTimer -= dt;
        if (asteroidTimer <= 0) {
            asteroidTimer = 45f;
            w.bodies.removeIf(b -> b.dead);
            respawnAsteroids();
        }
    }

    private void extract(Unit u, float dt) {
        Body p = u.home;
        if (p == null || p.type == null || p.type.rare == null) return;
        RareItem item = p.type.rare;
        float interval = item == RareItem.RELIC_FRAGMENT ? 90f : 45f;
        u.work += dt * u.owner.outpostMult();
        if (u.work >= interval) {
            u.work -= interval;
            u.owner.rare[item.ordinal()]++;
            if (u.owner == w.player) w.text(u.x, u.y + 20, "+1 " + item.shortName, item.color);
        }
    }

    /** Energy per second from a Solar Collector (harvest research and difficulty apply). */
    public float solarRate(Unit u) {
        return ConstructionSystem.SOLAR_RATE * u.owner.harvestMult() * incomeMult(u.owner);
    }

    /** Metal per second while a miner has something to mine. */
    public float minerRate(Unit u) {
        Body b = u.body;
        float base = b != null && b.kind == Body.Kind.PLANET ? ConstructionSystem.MINER_PLANET_RATE : ConstructionSystem.MINER_RATE;
        return base * u.owner.harvestMult() * u.owner.metalMult() * incomeMult(u.owner);
    }

    /** Mines its asteroid until empty, then the next asteroid within reach; metal planets never run out. */
    private void mine(Unit u, float dt) {
        Body b = u.body;
        if (b == null || b.dead || !b.harvestable()) {
            u.body = b = minerTarget(u);
            if (b == null) return; // depleted: nothing left in reach
        }
        float amt = minerRate(u) * dt;
        if (b.kind == Body.Kind.ASTEROID) {
            amt = Math.min(amt, b.metal);
            b.metal -= amt;
            if (b.metal <= 0.01f) b.dead = true;
        }
        u.owner.metal += amt;
    }

    /** The miner's own planet if it is minable, else the nearest live asteroid within reach. */
    public Body minerTarget(Unit u) {
        Body home = u.home;
        if (home != null && !home.dead && home.kind == Body.Kind.PLANET && home.harvestable()) return home;
        Body best = null;
        float bd = ConstructionSystem.MINER_REACH;
        for (Body b : w.bodies) {
            if (b.dead || b.kind != Body.Kind.ASTEROID) continue;
            float d = u.dist(b.x, b.y) - b.radius;
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /** Passive energy and metal per second from a settled planet (much slower than harvesting). */
    public static float colonyIncome(Body planet) {
        return planet.type != null && planet.type.housing >= 25 ? 1.5f : 1.0f;
    }

    public float capacity(Unit u) {
        return 30f * u.owner.cargoMult();
    }

    public void harvester(Unit u, float dt) {
        switch (u.order) {
            case BUILD:
                w.construction.update(u, dt);
                return;
            case MOVE:
                goal(u, u.tx, u.ty, 6);
                if (u.dist(u.tx, u.ty) < u.radius + 10) u.idle();
                return;
            case HARVEST:
                break;
            default:
                return;
        }
        if (u.fleeTimer > 0) { // running from a raid (AutoDefense); the harvest assignment in u.body is kept
            u.fleeTimer -= dt;
            Unit safe = w.nearestDropoff(u.owner, u.x, u.y);
            if (safe != null) {
                goal(u, safe.x, safe.y, safe.radius + 8);
                if (u.cargo > 0 && u.dist(safe) < safe.radius + u.radius + 22) deliver(u);
            }
            if (u.fleeTimer <= 0) u.harvestState = u.cargo > 0 ? 2 : 0;
            return;
        }
        Body b = u.body;
        if (b == null || b.dead || !b.harvestable()) {
            boolean wantEnergy = b != null ? b.kind == Body.Kind.STAR : u.owner.energy <= u.owner.metal;
            b = pickResource(u, wantEnergy, b != null ? b.x : u.x, b != null ? b.y : u.y);
            u.body = b;
            if (b == null) {
                if (u.cargo > 0) u.harvestState = 2;
                else { u.idle(); return; }
            }
        }
        float cap = capacity(u);
        switch (u.harvestState) {
            case 0: // travelling to resource
                if (u.cargo >= cap) { u.harvestState = 2; break; }
                goal(u, b.x, b.y, b.radius + 12);
                if (u.dist(b.x, b.y) < b.radius + u.radius + 20) u.harvestState = 1;
                break;
            case 1: { // gathering
                boolean energy = b.kind == Body.Kind.STAR;
                if (u.cargo > 0 && u.cargoEnergy != energy) { u.harvestState = 2; break; }
                u.cargoEnergy = energy;
                float rate = b.harvestRate() * u.owner.harvestMult() * (energy ? 1f : u.owner.metalMult());
                float amt = Math.min(rate * dt, cap - u.cargo);
                if (b.kind == Body.Kind.ASTEROID) {
                    amt = Math.min(amt, b.metal);
                    b.metal -= amt;
                    if (b.metal <= 0.01f) b.dead = true;
                }
                u.cargo += amt;
                if (u.cargo >= cap - 0.01f || b.dead) u.harvestState = 2;
                break;
            }
            default: { // returning
                Unit drop = w.nearestDropoff(u.owner, u.x, u.y);
                if (drop == null) { u.idle(); return; }
                goal(u, drop.x, drop.y, drop.radius + 8);
                if (u.dist(drop) < drop.radius + u.radius + 22) {
                    deliver(u);
                    u.harvestState = 0;
                }
                break;
            }
        }
    }

    /** Unloads a harvester's cargo; rivals get the difficulty's income multiplier. */
    private void deliver(Unit u) {
        float amt = u.cargo * incomeMult(u.owner);
        if (u.cargoEnergy) u.owner.energy += amt;
        else u.owner.metal += amt;
        u.cargo = 0;
    }

    private float incomeMult(Faction f) {
        return f.kind == Faction.Kind.RIVAL ? w.difficulty.rivalIncome : 1f;
    }

    private void goal(Unit u, float x, float y, float stop) {
        u.hasGoal = true;
        u.gx = x;
        u.gy = y;
        u.stopDist = stop;
    }

    /** Sends a harvester to whichever resource type has fewer of this faction's harvesters on it. */
    public void autoAssign(Unit u) {
        int onEnergy = 0, onMetal = 0;
        for (Unit h : w.units) {
            if (h == u || h.dead || h.owner != u.owner || !h.s.harvester || h.body == null || h.order != Order.HARVEST) continue;
            if (h.body.kind == Body.Kind.STAR) onEnergy++;
            else onMetal++;
        }
        boolean energy = onEnergy != onMetal ? onEnergy < onMetal : u.owner.energy <= u.owner.metal;
        Body b = pickResource(u, energy, u.x, u.y);
        if (b != null) assign(u, b);
    }

    public void assign(Unit u, Body b) {
        u.cancelBuild();
        u.order = Order.HARVEST;
        u.body = b;
        u.target = null;
        u.harvestState = u.cargo > 0 && u.cargoEnergy != (b.kind == Body.Kind.STAR) ? 2 : 0;
    }

    /** Picks a nearby resource, spreading harvesters out and avoiding guarded planets. */
    public Body pickResource(Unit u, boolean energy, float nearX, float nearY) {
        Body best = pick(u.owner, energy, nearX, nearY);
        if (best == null) best = pick(u.owner, !energy, nearX, nearY);
        return best;
    }

    private Body pick(Faction f, boolean energy, float nearX, float nearY) {
        Unit drop = w.nearestDropoff(f, nearX, nearY);
        float ox = drop != null ? drop.x : nearX, oy = drop != null ? drop.y : nearY;
        Body best = null;
        float bestScore = Float.MAX_VALUE;
        for (Body b : w.bodies) {
            if (!b.harvestable()) continue;
            if ((b.kind == Body.Kind.STAR) != energy) continue;
            if (b.guarded()) continue;
            float d = (float) Math.hypot(b.x - ox, b.y - oy);
            if (d > 4500) continue;
            int load = 0;
            for (Unit h : w.units) if (!h.dead && h.body == b && h.s.harvester) load++;
            float score = d + load * (b.kind == Body.Kind.STAR ? 120 : 260);
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    private void respawnAsteroids() {
        int alive = 0;
        for (Body b : w.bodies) if (b.kind == Body.Kind.ASTEROID && !b.dead) alive++;
        if (alive >= w.initialAsteroids * 0.75f) return;
        for (int attempt = 0; attempt < 40; attempt++) {
            float x = 800 + w.rng.nextFloat() * (World.SIZE - 1600);
            float y = 800 + w.rng.nextFloat() * (World.SIZE - 1600);
            boolean ok = true;
            for (Body b : w.bodies) if (!b.dead && Math.hypot(b.x - x, b.y - y) < b.radius + 450) { ok = false; break; }
            if (!ok) continue;
            for (Faction f : w.factions)
                if (f.mothership != null && !f.mothership.dead && f.mothership.dist(x, y) < 1400) ok = false;
            if (!ok) continue;
            GalaxyGenerator.asteroidField(w, w.rng, x, y, 5 + w.rng.nextInt(4));
            if (w.player.vision.isExplored(x, y)) w.announce("A new asteroid field has drifted into a known sector.", World.INFO);
            return;
        }
    }
}
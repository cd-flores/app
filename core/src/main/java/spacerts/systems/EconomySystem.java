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
        this.events = new java.util.Random(w.seed * 977 + 5);
    }

    public void update(float dt) {
        for (int i = 0, n = w.units.size(); i < n; i++) {
            Unit u = w.units.get(i);
            if (u.dead) continue;
            if (u.cls == ShipClass.OUTPOST) extract(u, dt);
            else if (u.cls == ShipClass.SOLAR) {
                u.owner.energy += solarRate(u) * dt;
                if (w.loot.nextFloat() < SOLAR_STAR_CHANCE / 60f * dt) w.grantRare(u.owner, RareItem.STAR_ESSENCE, 1, u.x, u.y);
            } else if (u.cls == ShipClass.MINER) mine(u, dt);
            else if (u.cls == ShipClass.COLONY && u.home != null && u.owner.isMajor()) {
                float rate = colonyIncome(u.home) * incomeMult(u.owner);
                u.owner.energy += rate * dt;
                u.owner.metal += rate * dt;
            }
        }
        for (int i = 0, n = w.bodies.size(); i < n; i++) {
            Body b = w.bodies.get(i);
            if (b.isWreck() && !b.dead && (b.life -= dt) <= 0) b.dead = true; // drifted away
        }
        asteroidTimer -= dt;
        if (asteroidTimer <= 0) {
            asteroidTimer = 45f;
            w.bodies.removeIf(b -> b.dead);
            respawnAsteroids();
        }
        showerTimer -= dt;
        if (showerTimer <= 0) { // once a minute, every system rolls for an asteroid shower
            showerTimer = 60f;
            for (spacerts.world.StarSystem s : w.galaxy.systems) {
                float chance = !w.galaxy.hive || s.archetype == spacerts.world.SystemArchetype.BELT ? SHOWER_CHANCE_BELT : SHOWER_CHANCE;
                if (events.nextFloat() < chance) asteroidShower(s);
            }
        }
    }

    // ------------------------------------------------------------------ asteroid showers

    /** Chance per minute of an asteroid shower: 1 in 60 per hex, 1 in 20 in Asteroid Belts (and the classic map). */
    public static final float SHOWER_CHANCE = 1f / 60f, SHOWER_CHANCE_BELT = 1f / 20f;
    /** Chance a shower brings a giant asteroid (millions of metal) along. */
    public static final float SHOWER_GIANT = 0.06f, SHOWER_GIANT_BELT = 0.12f;
    /** No new showers in a system that already has this many asteroids. */
    public static final int SHOWER_CAP = 180, SHOWER_CAP_BELT = 360;

    private float showerTimer = 60f;
    /** Own random stream so showers never shift combat or generation randomness. */
    private final java.util.Random events;

    /** A fresh asteroid field drifts into system s (sometimes with a giant). Returns false if s is full or crowded. */
    public boolean asteroidShower(spacerts.world.StarSystem s) {
        int rocks = 0;
        for (Body b : s.bodies) if (!b.dead && b.kind == Body.Kind.ASTEROID) rocks++;
        boolean belt = !w.galaxy.hive || s.archetype == spacerts.world.SystemArchetype.BELT;
        if (rocks >= (belt ? SHOWER_CAP_BELT : SHOWER_CAP)) return false; // keeps body counts sane over days of play
        for (int attempt = 0; attempt < 30; attempt++) {
            float[] p = s.randomPoint(events, 700);
            boolean ok = true;
            for (Body b : s.bodies) if (!b.dead && Math.hypot(b.x - p[0], b.y - p[1]) < b.radius + 400) { ok = false; break; }
            for (Faction f : w.factions)
                if (ok && f.mothership != null && !f.mothership.dead && f.mothership.dist(p[0], p[1]) < 900) ok = false;
            if (!ok) continue;
            GalaxyGenerator.asteroidField(w, events, p[0], p[1], 5 + events.nextInt(5));
            boolean giant = events.nextFloat() < (belt ? SHOWER_GIANT_BELT : SHOWER_GIANT);
            if (giant) {
                double a = events.nextDouble() * Math.PI * 2;
                GalaxyGenerator.giantAsteroid(w, events, p[0] + (float) Math.cos(a) * 320, p[1] + (float) Math.sin(a) * 320);
            }
            boolean known = w.player.vision.isExplored(p[0], p[1]) || s.owned();
            if (known) {
                String where = w.galaxy.hive ? " in " + s.name : "";
                w.announce(giant ? "Asteroid shower" + where + " - with a GIANT asteroid holding millions of metal!"
                        : "Asteroid shower" + where + ": a fresh field has drifted in.", giant ? World.GOOD : World.INFO);
                if (giant) w.alert(p[0], p[1]);
            }
            return true;
        }
        return false;
    }

    private void extract(Unit u, float dt) {
        Body p = u.home;
        if (p == null || p.type == null || p.type.rare == null) return;
        RareItem item = p.type.rare;
        float interval = item == RareItem.RELIC_FRAGMENT ? 90f : 45f;
        u.work += dt * u.owner.outpostMult();
        if (u.work >= interval) {
            u.work -= interval;
            w.grantRare(u.owner, item, 1, u.x, u.y);
            if (item == RareItem.RELIC_FRAGMENT && w.loot.nextFloat() < RUINS_EXOTIC_CHANCE)
                w.grantRare(u.owner, RareItem.EXOTIC_MATTER, 1, u.x, u.y + 18);
        }
    }

    // ---- scarce finds: low drop chances
    /** Per load of asteroid metal / star energy a harvester unloads. */
    public static final float ASTEROID_DARK_CHANCE = 0.02f, STAR_ESSENCE_CHANCE = 0.01f;
    /** Per minute of work for an Asteroid Miner / Solar Collector. */
    public static final float MINER_DARK_CHANCE = 0.04f, SOLAR_STAR_CHANCE = 0.04f;
    /** Per relic extracted at a Ruins outpost; per wreck salvaged to the end. */
    public static final float RUINS_EXOTIC_CHANCE = 0.12f, WRECK_NEUTRON_CHANCE = 0.08f;

    /** The last metal was taken out of a wreck by f: hand over its loot (and maybe a Neutronium find). */
    private void wreckEmptied(Body b, Faction f, float x, float y) {
        int neutron = b.lootNeutron + (w.loot.nextFloat() < WRECK_NEUTRON_CHANCE ? 1 : 0);
        w.grantRare(f, RareItem.NEUTRONIUM, neutron, x, y);
        w.grantRare(f, RareItem.EXOTIC_MATTER, b.lootExotic, x, y + 18);
        b.lootNeutron = b.lootExotic = 0;
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
            if (b == null) { // field mined out: crawl to the nearest asteroid clump in this system
                relocateMiner(u, dt);
                return;
            }
        }
        u.relocating = false;
        float amt = minerRate(u) * dt;
        if (b.depletes()) {
            amt = (float) Math.min(amt, b.metal);
            b.metal -= amt;
            if (b.metal <= 0.01f) b.dead = true;
        }
        u.owner.metal += amt;
        if (b.kind == Body.Kind.ASTEROID && w.loot.nextFloat() < MINER_DARK_CHANCE / 60f * dt)
            w.grantRare(u.owner, RareItem.DARK_MATTER, 1, u.x, u.y);
    }

    /** Speed at which an Asteroid Miner crawls to the next field once its own runs dry. */
    public static final float MINER_CRAWL = 15f;

    /** Moves a mined-out Asteroid Miner slowly toward the nearest live asteroid in its own system. */
    private void relocateMiner(Unit u, float dt) {
        spacerts.world.StarSystem sys = w.systemAt(u.x, u.y);
        Body best = null;
        double bd = Double.MAX_VALUE;
        for (Body c : sys.bodies) {
            if (c.dead || c.kind != Body.Kind.ASTEROID) continue;
            double d = Math.hypot(c.x - u.x, c.y - u.y);
            if (d < bd) { bd = d; best = c; }
        }
        u.relocating = best != null;
        if (best == null) return; // nothing left anywhere in the system: it waits for a shower
        float dx = best.x - u.x, dy = best.y - u.y, d = (float) Math.hypot(dx, dy);
        float stop = best.radius + ConstructionSystem.MINER_REACH * 0.5f;
        if (d <= stop) return; // close enough: minerTarget picks it up next tick
        float step = Math.min(d - stop, MINER_CRAWL * dt);
        float nx = u.x + dx / d * step, ny = u.y + dy / d * step;
        u.x = sys.clampX(nx, ny);
        u.y = sys.clampY(nx, ny);
        u.angle = (float) Math.atan2(dy, dx);
    }

    /** The miner's own planet if it is minable, else the nearest live asteroid within reach. */
    public Body minerTarget(Unit u) {
        Body home = u.home;
        if (home != null && !home.dead && home.kind == Body.Kind.PLANET && home.harvestable()) return home;
        Body best = null;
        float bd = ConstructionSystem.MINER_REACH;
        for (Body b : w.systemAt(u.x, u.y).bodies) {
            if (b.dead || b.kind != Body.Kind.ASTEROID) continue;
            float d = u.dist(b.x, b.y) - b.radius;
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /** Passive energy and metal per second from a settled planet (much slower than harvesting). */
    public static float colonyIncome(Body planet) {
        return planet.type == spacerts.world.PlanetType.DEFENDED ? 1.5f : 1.0f;
    }

    public float capacity(Unit u) {
        return u.s.cargo * u.owner.cargoMult();
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
                boolean drones = b.isWreck() && u.owner.has(spacerts.research.Tech.SALVAGE_DRONES); // +50% speed, +25% metal
                float rate = b.harvestRate() * u.s.mineMult * u.owner.harvestMult() * (energy ? 1f : u.owner.metalMult()) * (drones ? 1.5f : 1f);
                float amt = Math.min(rate * dt, (cap - u.cargo) / (drones ? 1.25f : 1f));
                if (b.depletes()) {
                    amt = (float) Math.min(amt, b.metal);
                    b.metal -= amt;
                    if (b.metal <= 0.01f) {
                        b.dead = true;
                        if (b.isWreck()) wreckEmptied(b, u.owner, u.x, u.y);
                    }
                }
                u.cargo += drones ? amt * 1.25f : amt;
                u.cargoAsteroid = b.kind == Body.Kind.ASTEROID;
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
        else u.owner.metal += amt * refineryBonus(u);
        // scarce finds turn up in a full-ish hold now and then
        if (u.cargo >= capacity(u) * 0.5f) {
            if (u.cargoEnergy && w.loot.nextFloat() < STAR_ESSENCE_CHANCE) w.grantRare(u.owner, RareItem.STAR_ESSENCE, 1, u.x, u.y);
            else if (!u.cargoEnergy && u.cargoAsteroid && w.loot.nextFloat() < ASTEROID_DARK_CHANCE)
                w.grantRare(u.owner, RareItem.DARK_MATTER, 1, u.x, u.y);
        }
        u.cargo = 0;
    }

    /** Ore Refinery: metal unloaded within REFINERY_RANGE of one of the owner's refineries is worth 25% more. */
    public static final float REFINERY_RANGE = 1500f, REFINERY_BONUS = 1.25f;

    private float refineryBonus(Unit u) {
        for (Unit d : u.owner.docks) // refineries are registered with the drop-off points
            if (!d.dead && d.cls == ShipClass.REFINERY && d.dist(u) < REFINERY_RANGE) return REFINERY_BONUS;
        return 1f;
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
        // harvesters work the system they are in (in the Hive they never wander through gates on their own)
        spacerts.world.StarSystem here = w.systemAt(nearX, nearY);
        for (Body b : here.bodies) {
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
            float x, y;
            if (w.galaxy.hive) {
                spacerts.world.StarSystem s = w.galaxy.systems.get(w.rng.nextInt(w.galaxy.systems.size()));
                float[] p = s.randomPoint(w.rng, 800);
                x = p[0];
                y = p[1];
            } else {
                x = 800 + w.rng.nextFloat() * (spacerts.world.Galaxy.CLASSIC_SIZE - 1600);
                y = 800 + w.rng.nextFloat() * (spacerts.world.Galaxy.CLASSIC_SIZE - 1600);
            }
            boolean ok = true;
            for (Body b : w.systemAt(x, y).bodies) if (!b.dead && Math.hypot(b.x - x, b.y - y) < b.radius + 450) { ok = false; break; }
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
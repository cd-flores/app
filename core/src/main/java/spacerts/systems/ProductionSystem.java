package spacerts.systems;

import spacerts.research.RareItem;
import spacerts.research.Tech;
import spacerts.units.Order;
import spacerts.units.ProductionQueue;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/** Build queues on producers (mothership, shipyards, hub, colonies) and research progress. */
public class ProductionSystem {
    private final World w;

    public ProductionSystem(World w) {
        this.w = w;
    }

    public void update(float dt) {
        for (int i = 0, n = w.units.size(); i < n; i++) {
            Unit p = w.units.get(i);
            if (p.dead || p.queue == null || p.queue.isEmpty()) continue;
            ShipClass c = p.queue.items.get(0);
            p.queue.progress += dt * p.owner.buildMult() * tierSpeed(p) / c.s.buildTime;
            if (p.queue.progress >= 1f) {
                p.queue.removeAt(0);
                p.queue.progress = 0;
                deliver(p, c);
            }
        }
        for (Faction f : w.factions) {
            if (f.researching == null || f.eliminated) continue;
            if (f.hub == null || f.hub.dead) continue;
            f.researchProgress += dt * researchSpeed(f) / f.researching.time;
            if (f.researchProgress >= 1f) complete(f, f.researching);
        }
    }

    /** Ship Factory upgrades: each tier above I builds 30% faster at that factory. */
    public static float tierSpeed(Unit p) {
        return p.cls == ShipClass.FACTORY ? 1f + 0.3f * (p.tier - 1) : 1f;
    }

    /** Each Research Hub (lab) tier above I researches 25% faster. */
    public static float researchSpeed(Faction f) {
        return 1f + 0.25f * Math.max(0, f.hubTier() - 1);
    }

    private void deliver(Unit p, ShipClass c) {
        Faction f = p.owner;
        if (c == ShipClass.STELLAR_REFINEMENT) { // endgame stacking upgrade
            f.refinement = Math.min(Faction.REFINEMENT_MAX, f.refinement + 1);
            w.ring(p.x, p.y, p.radius * 2.5f, new com.badlogic.gdx.graphics.Color(1f, 0.9f, 0.5f, 1f), 0.8f);
            w.notify(f, "Stellar Refinement level " + f.refinement + "/" + Faction.REFINEMENT_MAX + ": mining and energy +"
                    + Math.round(Faction.REFINEMENT_STEP * 100 * f.refinement) + "%.", World.GOOD);
            return;
        }
        if (c.isUpgrade()) { // tier upgrades raise the producer instead of spawning anything
            float hpFrac = p.hp / p.maxHp(), shFrac = p.maxShield() > 0 ? p.shield / p.maxShield() : 0;
            p.tier = Math.max(p.tier, c.s.upgradeTo);
            p.hp = p.maxHp() * hpFrac;
            p.shield = p.maxShield() * shFrac;
            w.ring(p.x, p.y, p.radius * 3f, f.color, 1.2f);
            w.notify(f, p.s.name + " upgraded to tier " + roman(p.tier) + "!", World.GOOD);
            return;
        }
        if (c == ShipClass.SHIPYARD) { // legacy queues from older saves
            Body planet = p.home;
            if (planet == null) return;
            float a = w.rng.nextFloat() * 6.283f;
            Unit yard = w.spawn(ShipClass.SHIPYARD, f, planet.x + (float) Math.cos(a) * (planet.radius + 55), planet.y + (float) Math.sin(a) * (planet.radius + 55));
            yard.home = planet;
            planet.shipyard = yard;
            w.notify(f, "Orbital Shipyard completed at " + planet.name, World.GOOD);
            return;
        }
        // where the new ships go: the producer's own rally point, else the faction's rally flag (warships only)
        boolean toFlag = !p.hasRally && f.hasFlag && c.isWarship();
        float gx = p.hasRally ? p.rallyX : f.flagX, gy = p.hasRally ? p.rallyY : f.flagY;
        boolean rally = p.hasRally || toFlag;
        float baseA = rally ? (float) Math.atan2(gy - p.y, gx - p.x) : w.rng.nextFloat() * 6.283f;
        for (int i = 0; i < c.s.squad; i++) {
            float a = baseA + (i - (c.s.squad - 1) / 2f) * 0.35f;
            float sx = p.x + (float) Math.cos(a) * (p.radius + c.s.radius + 6);
            float sy = p.y + (float) Math.sin(a) * (p.radius + c.s.radius + 6);
            Unit u = w.spawn(c, f, sx, sy);
            if (c.s.harvester && !p.hasRally) {
                w.economy.autoAssign(u);
            } else if (toFlag) {
                // spread arrivals around the flag so they don't stack
                float sa = (u.id * 2.39996f) % 6.2832f, sr = 30f + (u.id % 5) * 22f;
                u.command(Order.ATTACK_MOVE, gx + (float) Math.cos(sa) * sr, gy + (float) Math.sin(sa) * sr);
            } else if (p.hasRally) {
                u.command(Order.MOVE, p.rallyX + (i - 1) * 25f, p.rallyY + (i % 2) * 25f);
            } else {
                u.command(Order.MOVE, p.x + (float) Math.cos(a) * (p.radius + 70 + i * 10), p.y + (float) Math.sin(a) * (p.radius + 70 + i * 10));
            }
        }
    }

    /** Reason this producer cannot build c right now (ignoring cost), or null. */
    public String blocker(Unit p, ShipClass c) {
        Faction f = p.owner;
        boolean listed = false;
        for (ShipClass b : p.cls.buildList()) if (b == c) listed = true;
        if (!listed) return p.s.name + " can't build " + c.s.name;
        if (c.s.tech != null && !f.has(c.s.tech)) return "Requires research: " + c.s.tech.title;
        if (c.isUpgrade()) {
            if (p.tier >= c.s.upgradeTo) return "Already tier " + roman(p.tier);
            if (p.tier < c.s.upgradeTo - 1) return "Upgrade to tier " + roman(c.s.upgradeTo - 1) + " first";
            for (ShipClass q : p.queue.items) if (q.isUpgrade()) return "Upgrade already in progress";
        }
        if (p.cls == ShipClass.FACTORY && c.s.factoryTier > p.tier) return "Requires a tier " + roman(c.s.factoryTier) + " Ship Factory";
        if (p.cls == ShipClass.MOTHERSHIP && !c.isUpgrade() && c.s.msTier > p.tier)
            return "Requires Mothership tier " + roman(c.s.msTier);
        if (c == ShipClass.STELLAR_REFINEMENT) {
            if (p.tier < 3) return "Requires Mothership tier III";
            if (f.hubTier() < 3) return "Requires a tier III Research Hub (Hub Expansion III)";
            if (w.queued(f, c) > 0) return "Stellar Refinement already in progress";
            if (f.refinement >= Faction.REFINEMENT_MAX) return "Stellar Refinement maxed (" + Faction.REFINEMENT_MAX + ")";
        }
        if (c == ShipClass.HUB && ((f.hub != null && !f.hub.dead) || w.queued(f, ShipClass.HUB) > 0)) return "Only one Research Hub allowed";
        if (c == ShipClass.SHIPYARD) {
            if (p.home == null) return "Must be built at a colony";
            if ((p.home.shipyard != null && !p.home.shipyard.dead) || !p.queue.items.isEmpty()) return "This colony already has a shipyard";
        }
        if (c.s.cap > 0) {
            int cap = c == ShipClass.LEVIATHAN ? f.leviathanCap() : c.s.cap;
            if (w.count(f, c) + w.queued(f, c) >= cap) return c.s.name + " limit reached (" + cap + ")";
        }
        int needPop = c.s.pop * c.s.squad;
        if (needPop > 0 && f.popUsed + needPop > f.popCap) return "Not enough housing - colonize habitable planets";
        if (p.queue.items.size() >= ProductionQueue.MAX) return "Queue full";
        return null;
    }

    public static String roman(int t) {
        return t <= 1 ? "I" : t == 2 ? "II" : "III";
    }

    public String enqueue(Unit p, ShipClass c) {
        if (p == null || p.dead || p.queue == null) return "No producer";
        String why = blocker(p, c);
        if (why != null) return why;
        Faction f = p.owner;
        int e = energyCost(f, c), m = metalCost(f, c);
        String miss = f.missing(e, m, c.s.rare);
        if (miss != null) return miss;
        f.pay(e, m, c.s.rare);
        p.queue.add(c, e, m);
        f.popUsed += c.s.pop * c.s.squad;
        return null;
    }

    public void cancel(Unit p, int index) {
        if (p == null || p.queue == null || index < 0 || index >= p.queue.items.size()) return;
        ShipClass c = p.queue.items.get(index);
        int[] paid = p.queue.removeAt(index); // refund what was paid, not today's price
        p.owner.refund(paid[0], paid[1], c.s.rare);
        if (index == 0) p.queue.progress = 0;
    }

    // ------------------------------------------------------------------ logarithmic warship pricing

    /** Price growth: price = base x (1 + PRICE_K x ln(1 + copies)). */
    public static final float PRICE_K = 0.35f;

    /** Build orders of c the faction already has: alive (counted per squad) plus queued. */
    public int copies(Faction f, ShipClass c) {
        return w.count(f, c) / Math.max(1, c.s.squad) + w.queued(f, c);
    }

    /** Stellar Refinement price growth: base x (1 + REFINEMENT_K x ln(1 + level)). */
    public static final float REFINEMENT_K = 1.0f;

    /** Warships get pricier the more copies of that type you have; Stellar Refinement with its level; the rest stays at base. */
    public float priceMult(Faction f, ShipClass c) {
        if (c == ShipClass.STELLAR_REFINEMENT) return 1f + REFINEMENT_K * (float) Math.log(1 + f.refinement + w.queued(f, c));
        if (!c.isWarship()) return 1f;
        return 1f + PRICE_K * (float) Math.log(1 + copies(f, c));
    }

    public int energyCost(Faction f, ShipClass c) {
        return scaled(c.s.energy, priceMult(f, c));
    }

    public int metalCost(Faction f, ShipClass c) {
        return scaled(c.s.metal, priceMult(f, c));
    }

    private static int scaled(int base, float mult) {
        if (base <= 0 || mult <= 1f) return base;
        return Math.round(base * mult / 5f) * 5; // tidy numbers
    }

    // ------------------------------------------------------------------ research

    public String researchBlocker(Faction f, Tech t) {
        if (f.has(t)) return "Already researched";
        if (f.hub == null || f.hub.dead) return "Requires a Research Hub";
        if (f.researching != null) return "Already researching " + f.researching.title;
        if (f.hubTier() < t.tier) return "Requires Hub tier " + t.tier;
        Tech pre = t.prereq();
        if (pre != null && !f.has(pre)) return "Requires " + pre.title;
        return null;
    }

    public String startResearch(Faction f, Tech t) {
        String why = researchBlocker(f, t);
        if (why != null) return why;
        String miss = f.missing(t.energy, t.metal, t.rare);
        if (miss != null) return miss;
        f.pay(t.energy, t.metal, t.rare);
        f.researching = t;
        f.researchProgress = 0;
        return null;
    }

    public void cancelResearch(Faction f) {
        if (f.researching == null) return;
        Tech t = f.researching;
        f.refund(t.energy, t.metal, t.rare);
        f.researching = null;
        f.researchProgress = 0;
    }

    public void complete(Faction f, Tech t) {
        List<Unit> mine = new ArrayList<>();
        List<Float> oldMax = new ArrayList<>();
        for (Unit u : w.units)
            if (!u.dead && u.owner == f) {
                mine.add(u);
                oldMax.add(u.maxHp());
            }
        f.techs.add(t);
        f.researching = null;
        f.researchProgress = 0;
        for (int i = 0; i < mine.size(); i++) {
            Unit u = mine.get(i);
            float nm = u.maxHp();
            if (nm != oldMax.get(i)) u.hp *= nm / oldMax.get(i);
        }
        if (t == Tech.PLANET_TURRETS)
            for (Unit u : mine) if (u.cls == ShipClass.COLONY && u.home != null) w.spawnPlanetTurrets(u.home, f);
        w.notify(f, "Research complete: " + t.title + " - " + t.desc, World.GOOD);
    }

    /** Text such as "120E 80M 2 Pyro". */
    public static String costText(int e, int m, int[] rare) {
        StringBuilder sb = new StringBuilder();
        if (e > 0) sb.append(e).append("E ");
        if (m > 0) sb.append(m).append("M ");
        String r = RareItem.describe(rare);
        if (!r.isEmpty()) sb.append(r);
        return sb.toString().trim();
    }
}
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
            p.queue.progress += dt * p.owner.buildMult() * tierSpeed(p) * (p.owner.warpTen ? 10f : 1f) / c.s.buildTime; // WarpTen cheat
            if (p.queue.progress >= 1f) {
                p.queue.removeAt(0);
                p.queue.progress = 0;
                deliver(p, c);
            }
        }
        for (Faction f : w.factions) {
            if (f.eliminated) continue;
            float rate = dt * researchSpeed(f) * (f.fastResearch ? 10f : 1f); // WhoIsJohnGalt cheat
            if (f.researching != null && f.labUp(f.hub)) {
                f.researchProgress += rate / f.researching.time;
                if (f.researchProgress >= 1f) complete(f, f.researching);
            }
            if (f.researching2 != null && f.labUp(f.hub2)) { // the second lab works in parallel
                f.researchProgress2 += rate / f.researching2.time;
                if (f.researchProgress2 >= 1f) complete(f, f.researching2);
            }
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
        if (c.isCommandUnlock()) { // Command Center: a tactic or tactic upgrade, nothing spawns
            spacerts.units.Tactic t = c.unlocksTactic();
            spacerts.units.TacticUpgrade up = c.unlocksUpgrade();
            if (t != null) {
                f.tactics.add(t);
                if (f.activeTactic == null) f.activeTactic = t;
                w.notify(f, "Tactic unlocked: " + t.title + ". Select it at the Command Center; press Y and attack to use it.", World.GOOD);
            } else if (up != null) {
                f.tacticUps.add(up);
                w.notify(f, "Tactic upgrade ready: " + up.title + ".", World.GOOD);
            }
            w.ring(p.x, p.y, p.radius * 2.5f, f.color, 0.8f);
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
        // Replenish: a replacement for a lost formation ship goes straight to the last tactical attack
        boolean replenishing = f.replenish[c.ordinal()] > 0 && f.hasTacticTarget;
        if (f.replenish[c.ordinal()] > 0) f.replenish[c.ordinal()]--;
        for (int i = 0; i < c.s.squad; i++) {
            float a = baseA + (i - (c.s.squad - 1) / 2f) * 0.35f;
            float sx = p.x + (float) Math.cos(a) * (p.radius + c.s.radius + 6);
            float sy = p.y + (float) Math.sin(a) * (p.radius + c.s.radius + 6);
            Unit u = w.spawn(c, f, sx, sy);
            if (replenishing) {
                u.command(Order.ATTACK_MOVE, f.tacticX + (float) Math.cos(u.id) * 40, f.tacticY + (float) Math.sin(u.id) * 40);
                continue;
            }
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
        if (c.s.tech != null && !f.has(c.s.tech) && !f.synergy) return "Requires research: " + c.s.tech.title;
        if (c.isCommandUnlock()) {
            spacerts.units.Tactic t = c.unlocksTactic();
            spacerts.units.TacticUpgrade up = c.unlocksUpgrade();
            if ((t != null && f.tactics.contains(t)) || (up != null && f.has(up))) return "Already unlocked";
            if (up != null && up.needs != null && !f.tactics.contains(up.needs) && !f.synergy) return "Requires the " + up.needs.title + " tactic";
            if (up != null && up.needs == null && f.tactics.isEmpty() && !f.synergy) return "Requires a tactic";
            if (w.queued(f, c) > 0) return "Already in progress";
        }
        if (c.isUpgrade()) {
            if (p.tier >= c.s.upgradeTo) return "Already tier " + roman(p.tier);
            if (p.tier < c.s.upgradeTo - 1) return "Upgrade to tier " + roman(c.s.upgradeTo - 1) + " first";
            for (ShipClass q : p.queue.items) if (q.isUpgrade()) return "Upgrade already in progress";
        }
        if (p.cls == ShipClass.FACTORY && c.s.factoryTier > p.tier && !f.synergy) return "Requires a tier " + roman(c.s.factoryTier) + " Ship Station";
        if (p.cls == ShipClass.MOTHERSHIP && !c.isUpgrade() && c.s.msTier > p.tier && !f.synergy)
            return "Requires Mothership tier " + roman(c.s.msTier);
        if (c == ShipClass.STELLAR_REFINEMENT) {
            if (p.tier < 3) return "Requires Mothership tier III";
            if (f.hubTier() < 3) return "Requires a tier III Research Hub (Hub Expansion III)";
            if (w.queued(f, c) > 0) return "Stellar Refinement already in progress";
            if (f.refinement >= Faction.REFINEMENT_MAX) return "Stellar Refinement maxed (" + Faction.REFINEMENT_MAX + ")";
        }
        if (c == ShipClass.HUB && f.hubsAlive() + w.queued(f, ShipClass.HUB) >= ConstructionSystem.maxHubs(f)) return "Research Hub limit reached";
        if (c == ShipClass.SHIPYARD) {
            if (p.home == null) return "Must be built at a colony";
            if ((p.home.shipyard != null && !p.home.shipyard.dead) || !p.queue.items.isEmpty()) return "This colony already has a shipyard";
        }
        if (c.s.cap > 0) {
            int cap = c == ShipClass.LEVIATHAN ? f.leviathanCap() : c.s.cap;
            if (w.count(f, c) + w.queued(f, c) >= cap) return c.s.name + " limit reached (" + cap + ")";
        }
        int needPop = c.s.pop * c.s.squad;
        if (needPop > 0 && f.popUsed + needPop > f.popCap && !f.noHousing) return "Not enough housing - colonize habitable planets";
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
    /** Bulwarks and snipers get pricier twice as fast per copy. */
    public static final float HEAVY_PRICE_K = 0.7f;

    public static float priceK(ShipClass c) {
        return c == ShipClass.BULWARK || c == ShipClass.LONGBOW || c == ShipClass.PHANTOM ? HEAVY_PRICE_K : PRICE_K;
    }

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
        return 1f + priceK(c) * (float) Math.log(1 + copies(f, c));
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
        if (f.hubsAlive() == 0) return "Requires a Research Hub";
        if (f.researchingNow(t)) return "Already researching " + t.title;
        if (freeLab(f) == 0) return f.hubsAlive() > 1 ? "Both labs are busy" : "Already researching " + (f.researching != null ? f.researching.title : f.researching2.title);
        if (f.hubTier() < t.tier && !f.synergy) return "Requires Hub tier " + t.tier;
        Tech pre = t.prereq();
        if (pre != null && !f.has(pre) && !f.synergy) return "Requires " + pre.title;
        return null;
    }

    public String startResearch(Faction f, Tech t) {
        String why = researchBlocker(f, t);
        if (why != null) return why;
        String miss = f.missing(t.energy, t.metal, t.rare);
        if (miss != null) return miss;
        f.pay(t.energy, t.metal, t.rare);
        if (freeLab(f) == 1) {
            f.researching = t;
            f.researchProgress = 0;
        } else {
            f.researching2 = t;
            f.researchProgress2 = 0;
        }
        return null;
    }

    /** The first standing lab with nothing to research: 1, 2, or 0 when none. */
    public static int freeLab(Faction f) {
        if (f.labUp(f.hub) && f.researching == null) return 1;
        if (f.labUp(f.hub2) && f.researching2 == null) return 2;
        return 0;
    }

    /** Cancels lab 1's research (refunded in full). */
    public void cancelResearch(Faction f) {
        if (f.researching != null) cancelResearch(f, f.researching);
    }

    /** Cancels whichever lab is researching t (refunded in full). */
    public void cancelResearch(Faction f, Tech t) {
        if (t == null) return;
        if (f.researching == t) {
            f.researching = null;
            f.researchProgress = 0;
        } else if (f.researching2 == t) {
            f.researching2 = null;
            f.researchProgress2 = 0;
        } else return;
        f.refund(t.energy, t.metal, t.rare);
    }

    public void complete(Faction f, Tech t) {
        complete(f, t, true);
    }

    /** Finishes research t for f (also used by the SharpAndShiny cheat, without the message). */
    public void complete(Faction f, Tech t, boolean announce) {
        List<Unit> mine = new ArrayList<>();
        List<Float> oldMax = new ArrayList<>();
        for (Unit u : w.units)
            if (!u.dead && u.owner == f) {
                mine.add(u);
                oldMax.add(u.maxHp());
            }
        f.techs.add(t);
        if (f.researching == t) {
            f.researching = null;
            f.researchProgress = 0;
        }
        if (f.researching2 == t) {
            f.researching2 = null;
            f.researchProgress2 = 0;
        }
        for (int i = 0; i < mine.size(); i++) {
            Unit u = mine.get(i);
            float nm = u.maxHp();
            if (nm != oldMax.get(i)) u.hp *= nm / oldMax.get(i);
        }
        if (t == Tech.PLANET_TURRETS)
            for (Unit u : mine) if (u.cls == ShipClass.COLONY && u.home != null) w.spawnPlanetTurrets(u.home, f);
        if (announce) w.notify(f, "Research complete: " + t.title + " - " + t.desc, World.GOOD);
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

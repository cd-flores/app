package spacerts.units;

import spacerts.research.RareItem;
import spacerts.research.Tech;

/** Stat block for a ShipClass, built with a fluent API. */
public class Spec {
    public final String name, desc;
    public int energy, metal, pop;
    public int[] rare = new int[RareItem.COUNT];
    public float hp = 100, shield, shieldRegen, armor, speed;
    public float range, minRange, damage, cooldown = 1f;
    public float vision = 400, radius = 10, buildTime = 10;
    public ArmorType type = ArmorType.LIGHT, bonusVs;
    public float bonusMult = 1f, splash, aura, healRange, healRate;
    /** Harvesters: cargo hold size and gathering speed multiplier. */
    public float cargo = 30, mineMult = 1f;
    /** Tier upgrades queued at a Mothership / Factory: the tier it raises the producer to (0 = not an upgrade). */
    public int upgradeTo;
    /** Ship Factory tier needed to build this ship; Mothership tier needed to construct this structure. */
    public int factoryTier = 1, msTier = 1;
    /** Only exists in the pirates-only Hive galaxy (Claim Beacon, Ascension Gate). */
    public boolean hiveOnly;
    public boolean piercing, mustStop, cloak, stationary, harvester, colonizer, surveyor, producer, internal, detector;
    public int drones, squad = 1, multi = 1, cap;
    /** Activated skills, each on its own hotkey (see Ability). */
    public Ability[] abilities = {};
    public Tech tech;

    public Spec(String name, String desc) {
        this.name = name;
        this.desc = desc;
    }

    /** Cost is per build order (whole squad); pop is per individual ship. */
    /** Flat energy surcharge added to the price of everything that costs something (balance pass). */
    public static final int ENERGY_SURCHARGE = 50;

    public Spec cost(int e, int m, int pop) {
        energy = e + (e > 0 || m > 0 ? ENERGY_SURCHARGE : 0);
        metal = m;
        this.pop = pop;
        return this;
    }
    public Spec rare(int pyro, int cryo, int plasma, int voidShard, int relic) { rare = RareItem.cost(pyro, cryo, plasma, voidShard, relic); return this; }
    /** Scarce-find costs (Dark Matter, Star Essence, Neutronium, Exotic Matter), added to any rare() cost. */
    public Spec rare2(int dark, int star, int neutron, int exotic) {
        rare = rare.clone();
        rare[5] = dark;
        rare[6] = star;
        rare[7] = neutron;
        rare[8] = exotic;
        return this;
    }
    public Spec hp(float v) { hp = v; return this; }
    public Spec shield(float v, float regen) { shield = v; shieldRegen = regen; return this; }
    public Spec armor(float v) { armor = v; return this; }
    public Spec speed(float v) { speed = v; return this; }
    public Spec weapon(float range, float dmg, float cd) { this.range = range; damage = dmg; cooldown = cd; return this; }
    public Spec minRange(float v) { minRange = v; return this; }
    public Spec bonus(ArmorType t, float mult) { bonusVs = t; bonusMult = mult; return this; }
    public Spec splash(float v) { splash = v; return this; }
    public Spec aura(float v) { aura = v; return this; }
    public Spec heal(float range, float rate) { healRange = range; healRate = rate; return this; }
    public Spec piercing() { piercing = true; return this; }
    public Spec mustStop() { mustStop = true; return this; }
    public Spec cloak() { cloak = true; return this; }
    public Spec stationary() { stationary = true; return this; }
    public Spec harvester() { harvester = true; return this; }
    public Spec cargo(float v) { cargo = v; return this; }
    public Spec upgrade(int toTier) { upgradeTo = toTier; internal = true; return this; }
    public Spec factoryTier(int t) { factoryTier = t; return this; }
    public Spec msTier(int t) { msTier = t; return this; }
    public Spec hiveOnly() { hiveOnly = true; return this; }
    public Spec mine(float v) { mineMult = v; return this; }
    public Spec colonizer() { colonizer = true; return this; }
    public Spec surveyor() { surveyor = true; return this; }
    public Spec producer() { producer = true; return this; }
    public Spec internal() { internal = true; return this; }
    public Spec detector() { detector = true; return this; }
    public Spec vision(float v) { vision = v; return this; }
    public Spec radius(float v) { radius = v; return this; }
    public Spec build(float v) { buildTime = v; return this; }
    public Spec type(ArmorType t) { type = t; return this; }
    public Spec drones(int n) { drones = n; return this; }
    public Spec squad(int n) { squad = n; return this; }
    public Spec multi(int n) { multi = n; return this; }
    public Spec cap(int n) { cap = n; return this; }
    public Spec ability(Ability a) {
        abilities = java.util.Arrays.copyOf(abilities, abilities.length + 1);
        abilities[abilities.length - 1] = a;
        return this;
    }

    public boolean has(Ability a) {
        for (Ability b : abilities) if (b == a) return true;
        return false;
    }
    public Spec tech(Tech t) { tech = t; return this; }

    public boolean hasRare() {
        for (int r : rare) if (r > 0) return true;
        return false;
    }

    public float dps() {
        return cooldown > 0 ? damage * multi / cooldown : 0;
    }
}

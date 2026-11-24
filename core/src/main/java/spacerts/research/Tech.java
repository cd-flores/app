package spacerts.research;

import com.badlogic.gdx.graphics.Color;

import static spacerts.research.RareItem.cost;

/** Research Hub tech tree. Costs energy, metal, rare items and time. */
public enum Tech {
    // ---- Military
    WEAPONS_1("Plasma Weapons I", Branch.MILITARY, 300, 300, 40, 1, null, "+15% weapon damage (all ships)", cost(2, 0, 0, 0, 0)),
    WEAPONS_2("Plasma Weapons II", Branch.MILITARY, 600, 600, 60, 2, "WEAPONS_1", "+15% more weapon damage", cost(5, 0, 0, 0, 1)),
    ARMOR_1("Reinforced Hulls I", Branch.MILITARY, 300, 400, 40, 1, null, "+15% hull HP (all ships)", cost(0, 2, 0, 0, 0)),
    LAST_STAND("Last Stand", Branch.MILITARY, 300, 250, 40, 1, null, "Infantry (Lancers, Drones) survive one killing blow with 1 HP; recharges after 30 s", cost(0, 2, 0, 0, 0)),
    ARMOR_2("Reinforced Hulls II", Branch.MILITARY, 600, 800, 60, 2, "ARMOR_1", "+15% more hull HP", cost(0, 5, 0, 0, 1)),
    SHIELDS("Shield Harmonics", Branch.MILITARY, 400, 300, 45, 1, null, "Shields recharge twice as fast", cost(0, 3, 0, 0, 0)),
    ENGINES("Ion Engines", Branch.MILITARY, 300, 200, 40, 1, null, "+15% ship speed", cost(0, 0, 2, 0, 0)),
    SENSORS("Deep Sensors", Branch.MILITARY, 250, 150, 35, 1, null, "+25% vision, Sensor Ping recharges faster", cost(0, 0, 0, 2, 0)),
    CARRIER_TECH("Carrier Doctrine", Branch.MILITARY, 500, 500, 50, 1, null, "Unlocks the Hive carrier", cost(0, 0, 0, 0, 0)),
    AEGIS_TECH("Aegis Project", Branch.MILITARY, 600, 600, 60, 2, null, "Unlocks Aegis: tank with a shield bubble for allies", cost(0, 4, 0, 0, 0)),
    PHANTOM_TECH("Phantom Project", Branch.MILITARY, 600, 500, 60, 2, null, "Unlocks Phantom: cloaked sniper", cost(0, 0, 4, 2, 0)),
    LEVIATHAN_TECH("Leviathan Project", Branch.MILITARY, 1500, 1500, 120, 3, null, "Unlocks the Leviathan capital ship", cost(0, 0, 0, 6, 3)),
    NOVA_CANNON("Nova Cannon", Branch.MILITARY, 800, 600, 70, 3, "LEVIATHAN_TECH", "Leviathan ability (Q): massive AoE blast", cost(6, 0, 0, 0, 1)),
    FLEET_COMMAND("Capital Fleet Command", Branch.MILITARY, 2000, 2000, 120, 3, "LEVIATHAN_TECH", "Leviathan limit 1 -> 3", cost(0, 0, 0, 0, 5)),
    // ---- Economy
    HARVEST_1("Efficient Collectors I", Branch.ECONOMY, 200, 150, 30, 1, null, "+25% harvest rate", cost(0, 0, 0, 0, 0)),
    HARVEST_2("Efficient Collectors II", Branch.ECONOMY, 500, 400, 45, 2, "HARVEST_1", "+25% more harvest rate", cost(0, 0, 1, 0, 0)),
    CARGO("Expanded Holds", Branch.ECONOMY, 250, 250, 30, 1, null, "+50% harvester cargo", cost(0, 0, 0, 0, 0)),
    DEEP_MINING("Deep Core Mining", Branch.ECONOMY, 300, 300, 40, 1, null, "+30% metal mining rate", cost(0, 0, 0, 1, 0)),
    SURVEY_DRILLS("Survey Drills", Branch.ECONOMY, 300, 200, 40, 1, null, "Outposts extract rare items 50% faster", cost(1, 1, 0, 0, 0)),
    // ---- Colony
    HOUSING("Arcology Habitats", Branch.COLONY, 400, 400, 45, 1, null, "+50% housing on colonies", cost(0, 1, 0, 0, 0)),
    PLANET_TURRETS("Planetary Defense Grid", Branch.COLONY, 500, 600, 50, 2, null, "Every colony gets 2 defense turrets", cost(2, 0, 0, 0, 0)),
    // ---- Mothership
    MS_ARMOR("Mothership Plating", Branch.MOTHERSHIP, 500, 700, 45, 1, null, "+40% mothership HP", cost(0, 2, 0, 0, 0)),
    MS_REPAIR("Self-Repair Nanites", Branch.MOTHERSHIP, 400, 400, 45, 1, null, "Mothership and structures slowly self-repair", cost(0, 0, 0, 1, 0)),
    RAPID_ASSEMBLY("Rapid Assembly", Branch.MOTHERSHIP, 500, 500, 45, 2, null, "+30% build speed everywhere", cost(0, 0, 2, 0, 0)),
    // ---- Hub
    HUB_TIER_2("Hub Expansion II", Branch.HUB, 600, 600, 60, 1, null, "Research Hub tier 2", cost(1, 1, 1, 1, 0)),
    HUB_TIER_3("Hub Expansion III", Branch.HUB, 1200, 1200, 90, 2, "HUB_TIER_2", "Research Hub tier 3", cost(0, 0, 0, 0, 2));

    public enum Branch {
        MILITARY("Military", new Color(0.95f, 0.4f, 0.35f, 1f)),
        ECONOMY("Economy", new Color(0.95f, 0.85f, 0.3f, 1f)),
        COLONY("Colony", new Color(0.4f, 0.9f, 0.5f, 1f)),
        MOTHERSHIP("Mothership", new Color(0.4f, 0.75f, 1f, 1f)),
        HUB("Hub", new Color(0.8f, 0.55f, 1f, 1f));

        public final String title;
        public final Color color;

        Branch(String title, Color color) {
            this.title = title;
            this.color = color;
        }
    }

    public final String title;
    public final Branch branch;
    public final int energy, metal;
    public final float time;
    public final int tier;
    private final String prereqName;
    public final String desc;
    public final int[] rare;

    Tech(String title, Branch branch, int energy, int metal, float time, int tier, String prereq, String desc, int[] rare) {
        this.title = title;
        this.branch = branch;
        this.energy = energy;
        this.metal = metal;
        this.time = time;
        this.tier = tier;
        this.prereqName = prereq;
        this.desc = desc;
        this.rare = rare;
    }

    public Tech prereq() {
        return prereqName == null ? null : Tech.valueOf(prereqName);
    }
}

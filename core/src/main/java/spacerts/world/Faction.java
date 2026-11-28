package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.ai.RivalAI;
import spacerts.research.RareItem;
import spacerts.research.Tech;
import spacerts.units.ShipClass;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class Faction {
    public enum Kind { PLAYER, RIVAL, NATIVES, PIRATES }

    public final int id;
    public final String name;
    public final Color color;
    /** Not final: a human who leaves a LAN game is handed to a bot. */
    public Kind kind;
    /** Which roster this empire builds from (flagship, builder, build lists, bot army). */
    public spacerts.units.Race race = spacerts.units.Race.COALITION;
    /** Totals for the end-of-match table. */
    public MatchStats stats = new MatchStats();

    /** Stockpiles are doubles: endgame economies (giant asteroids, refinement) reach millions. */
    public double energy, metal;
    public final int[] rare = new int[RareItem.COUNT];
    /** Which rare items this faction has ever had (scarce finds appear in the top bar once found). */
    public final boolean[] found = new boolean[RareItem.COUNT];
    public final EnumSet<Tech> techs = EnumSet.noneOf(Tech.class);
    public Tech researching;
    public float researchProgress;
    /** Second Research Hub (Mothership tier II): its own research, running alongside lab 1 (hub / researching). */
    public Unit hub2;
    public Tech researching2;
    public float researchProgress2;

    public boolean labUp(Unit h) { return h != null && !h.dead; }

    /** Living Research Hubs (0-2). */
    public int hubsAlive() { return (labUp(hub) ? 1 : 0) + (labUp(hub2) ? 1 : 0); }

    /** True when either lab is researching t. */
    public boolean researchingNow(Tech t) { return t != null && (researching == t || researching2 == t); }
    public boolean eliminated;
    /** Alliance: factions sharing a non-zero team are allies (shared vision, no friendly fire). 0 = no team. */
    public int team;
    /** Bot tuning for rivals (income, aggression, ship cap). */
    public Difficulty difficulty = Difficulty.NORMAL;

    public Unit mothership, hub;
    public int popUsed, popCap;
    /** Fog of war, sized to the galaxy by World (major factions only). */
    public VisionGrid vision;
    public RivalAI ai;

    /** Escalation bonuses (used by pirates). */
    public float extraHp, extraDmg;

    public float lastAttackedTime = -999, lastAttackX, lastAttackY, lastAlertTime = -999;
    public final List<Unit> detectors = new ArrayList<>();
    public final List<Unit> auras = new ArrayList<>();
    public final List<Unit> colonies = new ArrayList<>();
    /** Colonies, factories, shipyards and the mothership: where damaged ships go to repair (rebuilt each tick). */
    public final List<Unit> docks = new ArrayList<>();

    /** Rally flag: where newly built warships gather. */
    public boolean hasFlag;
    public float flagX, flagY;

    /** Player automation: idle ships defend your bases, harvesters flee raids, damaged ships retreat to Menders. */
    public boolean autoDefense = true;
    /** Rally Stations (V on the Mothership): stations hold hex-ring slots around the Mothership and move at its speed. */
    public boolean rallyStations;
    /** Command Center: unlocked tactics, the active one, and owned tactic upgrades. */
    public final EnumSet<spacerts.units.Tactic> tactics = EnumSet.noneOf(spacerts.units.Tactic.class);
    public spacerts.units.Tactic activeTactic;
    public final EnumSet<spacerts.units.TacticUpgrade> tacticUps = EnumSet.noneOf(spacerts.units.TacticUpgrade.class);
    /** Last tactical attack destination (Replenish sends replacements there). */
    public boolean hasTacticTarget;
    public float tacticX, tacticY;
    /** Replenish: replacement ships per class still owed to the last tactical attack. */
    public final int[] replenish = new int[ShipClass.values().length];
    /** Replenish: formation ships lost per class not yet re-queued (a squad is re-queued once all of it is lost). */
    public final int[] replenishLost = new int[ShipClass.values().length];

    public boolean has(spacerts.units.TacticUpgrade u) { return tacticUps.contains(u); }
    /** Cheats (single player): unlocked by the activation code; the toggles each cheat code flips. */
    public boolean cheatsUnlocked, cheatsUsed, godMode, noFog, warpTen, fastResearch, synergy, noHousing, noCooldowns, fastDecay, noDefeat;
    /** Upkeep weight (ship housing + 4 per station), recomputed every tick with the population. */
    public int upkeepPoints;
    /** Smoothed metal per second spent on repairs (HUD), and when the last out-of-metal warning went out. */
    public float repairSpend, repairSpentTick, lastRepairAlert = -999;
    public float lastSniperAlert = -999;

    public Faction(int id, String name, Color color, Kind kind) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.kind = kind;
    }

    public boolean isMajor() { return kind == Kind.PLAYER || kind == Kind.RIVAL; }
    public boolean isPlayer() { return kind == Kind.PLAYER; }

    /** True when the two factions fight each other: different factions not sharing a non-zero team. */
    public boolean hostileTo(Faction o) {
        return o != this && (team == 0 || o.team != team);
    }

    public boolean alliedWith(Faction o) {
        return !hostileTo(o);
    }

    public boolean has(Tech t) { return techs.contains(t); }

    public int hubTier() {
        if (hub == null || hub.dead) return 0;
        return 1 + (has(Tech.HUB_TIER_2) ? 1 : 0) + (has(Tech.HUB_TIER_3) ? 1 : 0);
    }

    // ---- tech multipliers
    public float dmgMult() {
        float m = 1f;
        if (has(Tech.WEAPONS_1)) m += 0.15f;
        if (has(Tech.WEAPONS_2)) m += 0.15f;
        return m * (1f + extraDmg);
    }

    public float hpMult(ShipClass c) {
        float m = 1f;
        if (c.isFlagship()) {
            if (has(Tech.MS_ARMOR)) m += 0.4f;
        } else if (!c.isBuilding()) {
            if (has(Tech.ARMOR_1)) m += 0.15f;
            if (has(Tech.ARMOR_2)) m += 0.15f;
            if (has(Tech.NEUTRONIUM_PLATING)) m += 0.2f;
        }
        return m * (1f + extraHp);
    }

    public float speedMult() { return (has(Tech.ENGINES) ? 1.15f : 1f) * (has(Tech.HYPERDRIVE) ? 1.1f : 1f); }
    public float visionMult() { return has(Tech.SENSORS) ? 1.25f : 1f; }
    public float shieldRegenMult() { return has(Tech.SHIELDS) ? 2f : 1f; }
    public float harvestMult() {
        return (1f + (has(Tech.HARVEST_1) ? 0.25f : 0) + (has(Tech.HARVEST_2) ? 0.25f : 0)) * refinementMult();
    }

    /** Stellar Refinement: the endgame stacking upgrade, +2% mining and energy per level (max 999). */
    public static final int REFINEMENT_MAX = 999;
    public static final float REFINEMENT_STEP = 0.02f;
    public int refinement;

    public float refinementMult() { return 1f + REFINEMENT_STEP * refinement; }
    public float cargoMult() { return has(Tech.CARGO) ? 1.5f : 1f; }
    public float metalMult() { return has(Tech.DEEP_MINING) ? 1.3f : 1f; }
    public float buildMult() { return (has(Tech.RAPID_ASSEMBLY) ? 1.3f : 1f) * (has(Tech.DARK_REACTORS) ? 1.3f : 1f); }
    public float housingMult() { return has(Tech.HOUSING) ? 1.5f : 1f; }
    public float outpostMult() { return has(Tech.SURVEY_DRILLS) ? 1.5f : 1f; }
    /** Mothership upgrade tier (1-3); 0 without a mothership. */
    public int msTier() { return mothership != null && !mothership.dead ? mothership.tier : 0; }
    public int leviathanCap() { return has(Tech.FLEET_COMMAND) ? 3 : 1; }

    // ---- resources
    public boolean canAfford(int e, int m, int[] r) {
        if (energy < e || metal < m) return false;
        for (int i = 0; i < RareItem.COUNT; i++) if (rare[i] < r[i]) return false;
        return true;
    }

    public String missing(int e, int m, int[] r) {
        if (energy < e) return "Not enough energy";
        if (metal < m) return "Not enough metal";
        for (RareItem it : RareItem.values()) if (rare[it.ordinal()] < r[it.ordinal()]) return "Not enough " + it.title;
        return null;
    }

    /** Income from mining, collectors, colonies and claimed hexes (counted as gathered). */
    public void earn(double e, double m) {
        energy += e;
        metal += m;
        stats.energyGathered += e;
        stats.metalGathered += m;
    }

    public void pay(int e, int m, int[] r) {
        energy -= e;
        metal -= m;
        stats.energySpent += e;
        stats.metalSpent += m;
        for (int i = 0; i < RareItem.COUNT; i++) rare[i] -= r[i];
    }

    public void refund(int e, int m, int[] r) {
        energy += e;
        metal += m;
        stats.energySpent -= e;
        stats.metalSpent -= m;
        for (int i = 0; i < RareItem.COUNT; i++) rare[i] += r[i];
    }
}

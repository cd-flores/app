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
    public final Kind kind;

    public float energy, metal;
    public final int[] rare = new int[RareItem.COUNT];
    public final EnumSet<Tech> techs = EnumSet.noneOf(Tech.class);
    public Tech researching;
    public float researchProgress;
    public boolean eliminated;

    public Unit mothership, hub;
    public int popUsed, popCap;
    public final VisionGrid vision;
    public RivalAI ai;

    /** Escalation bonuses (used by pirates). */
    public float extraHp, extraDmg;

    public float lastAttackedTime = -999, lastAttackX, lastAttackY, lastAlertTime = -999;
    public final List<Unit> detectors = new ArrayList<>();
    public final List<Unit> auras = new ArrayList<>();
    public final List<Unit> colonies = new ArrayList<>();

    /** Rally flag: where newly built warships gather. */
    public boolean hasFlag;
    public float flagX, flagY;

    /** Player automation: idle ships defend your bases, harvesters flee raids, damaged ships retreat to Menders. */
    public boolean autoDefense = true;
    public float lastSniperAlert = -999;

    public Faction(int id, String name, Color color, Kind kind) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.kind = kind;
        this.vision = isMajor() ? new VisionGrid(World.SIZE, 250f) : null;
    }

    public boolean isMajor() { return kind == Kind.PLAYER || kind == Kind.RIVAL; }
    public boolean isPlayer() { return kind == Kind.PLAYER; }

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
        if (c == ShipClass.MOTHERSHIP) {
            if (has(Tech.MS_ARMOR)) m += 0.4f;
        } else if (!c.isBuilding()) {
            if (has(Tech.ARMOR_1)) m += 0.15f;
            if (has(Tech.ARMOR_2)) m += 0.15f;
        }
        return m * (1f + extraHp);
    }

    public float speedMult() { return has(Tech.ENGINES) ? 1.15f : 1f; }
    public float visionMult() { return has(Tech.SENSORS) ? 1.25f : 1f; }
    public float shieldRegenMult() { return has(Tech.SHIELDS) ? 2f : 1f; }
    public float harvestMult() { return 1f + (has(Tech.HARVEST_1) ? 0.25f : 0) + (has(Tech.HARVEST_2) ? 0.25f : 0); }
    public float cargoMult() { return has(Tech.CARGO) ? 1.5f : 1f; }
    public float metalMult() { return has(Tech.DEEP_MINING) ? 1.3f : 1f; }
    public float buildMult() { return has(Tech.RAPID_ASSEMBLY) ? 1.3f : 1f; }
    public float housingMult() { return has(Tech.HOUSING) ? 1.5f : 1f; }
    public float outpostMult() { return has(Tech.SURVEY_DRILLS) ? 1.5f : 1f; }
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

    public void pay(int e, int m, int[] r) {
        energy -= e;
        metal -= m;
        for (int i = 0; i < RareItem.COUNT; i++) rare[i] -= r[i];
    }

    public void refund(int e, int m, int[] r) {
        energy += e;
        metal += m;
        for (int i = 0; i < RareItem.COUNT; i++) rare[i] += r[i];
    }
}

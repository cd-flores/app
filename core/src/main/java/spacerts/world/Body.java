package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;

/** A star, asteroid or planet. */
public class Body {
    /** WRECK: debris left by a destroyed ship; holds salvageable metal and drifts away after `life` seconds. */
    public enum Kind { STAR, ASTEROID, PLANET, WRECK, GATE }

    /** The system this body sits in (set by Galaxy.indexBodies). */
    public StarSystem system;
    /** Jump gates: the paired gate in the neighbouring system, and which side of this system the gate is on. */
    public Body link;
    public int side = -1;

    public boolean isGate() { return kind == Kind.GATE; }

    public static final float WRECK_LIFE = 300f;
    /** Seconds left before a wreck drifts away (wrecks only). */
    public float life;
    /** Scarce loot in a wreck, given to whoever salvages its last metal. */
    public int lootNeutron, lootExotic;

    public boolean isWreck() { return kind == Kind.WRECK; }

    /** Asteroid or wreck: a body with a finite metal store that disappears when emptied. */
    public boolean depletes() { return kind == Kind.ASTEROID || kind == Kind.WRECK; }

    public final Kind kind;
    public PlanetType type;
    public float x, y, radius;
    /** Doubles so giant asteroids holding millions still register a miner's tiny per-tick bites. */
    public double metal, maxMetal;
    /** A giant asteroid holding millions of metal. */
    public boolean giant;
    public String name = "";
    public final Color color = new Color(1, 1, 1, 1);
    public float guardRadius;
    public int maxGuards;
    public float guardTimer;
    public boolean dead;
    /**
     * Planet defense upgrade points spent on the colony here. A colony has MAX_DEFENSE points in total,
     * split however the owner likes between turrets and shield.
     */
    public int turretPoints, shieldPoints;
    public static final int MAX_DEFENSE = 5;
    public static final float SHIELD_PER_POINT = 400f, REGEN_PER_POINT = 8f;

    public int defensePoints() { return turretPoints + shieldPoints; }

    public Unit colony, outpost, shipyard;
    public final List<Unit> turrets = new ArrayList<>();
    public final List<Unit> guards = new ArrayList<>();

    public Body(Kind kind, float x, float y, float radius) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.radius = radius;
    }

    public boolean isPlanet() { return kind == Kind.PLANET; }

    public boolean habitable() { return kind == Kind.PLANET && type.habitable; }

    /** True while native turrets still stand on this planet. */
    public boolean guarded() {
        for (Unit t : turrets) if (!t.dead && t.owner.kind == Faction.Kind.NATIVES) return true;
        return false;
    }

    public int turretsAlive() {
        int n = 0;
        for (Unit t : turrets) if (!t.dead && t.owner.kind == Faction.Kind.NATIVES) n++;
        return n;
    }

    public boolean colonized() { return colony != null && !colony.dead; }

    public boolean colonizable() { return habitable() && !colonized() && !guarded(); }

    public boolean canSurvey() {
        return kind == Kind.PLANET && type.rare != null && (outpost == null || outpost.dead) && !guarded();
    }

    public boolean harvestable() {
        return !dead && (kind == Kind.STAR || kind == Kind.ASTEROID || kind == Kind.WRECK || (kind == Kind.PLANET && type.minable));
    }

    /** Base resource per second while a harvester is gathering. */
    public float harvestRate() {
        switch (kind) {
            case STAR: return 8f;
            case ASTEROID: return 7f;
            case WRECK: return 10f;
            default: return 4.2f;
        }
    }

    public Faction owner() { return colonized() ? colony.owner : null; }

    public float housing(Faction f) {
        return kind == Kind.PLANET ? type.housing * f.housingMult() : 0;
    }

    public String title() {
        switch (kind) {
            case STAR: return name + " (Star)";
            case ASTEROID: return giant ? (name.isEmpty() ? "Giant Asteroid" : name) : "Asteroid";
            case WRECK: return "Wreck (" + (int) metal + " metal)";
            case GATE: return name;
            default: return name + " - " + type.title;
        }
    }
}

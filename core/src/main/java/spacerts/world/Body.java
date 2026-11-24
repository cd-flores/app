package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;

/** A star, asteroid or planet. */
public class Body {
    public enum Kind { STAR, ASTEROID, PLANET }

    public final Kind kind;
    public PlanetType type;
    public float x, y, radius;
    public float metal, maxMetal;
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
        return !dead && (kind == Kind.STAR || kind == Kind.ASTEROID || (kind == Kind.PLANET && type.minable));
    }

    /** Base resource per second while a harvester is gathering. */
    public float harvestRate() {
        switch (kind) {
            case STAR: return 8f;
            case ASTEROID: return 7f;
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
            case ASTEROID: return "Asteroid";
            default: return name + " - " + type.title;
        }
    }
}

package spacerts.world;

import com.badlogic.gdx.graphics.Color;

/** What a Hive system is mostly made of, so neighbouring maps feel different. */
public enum SystemArchetype {
    CLASSIC("Open Space", "", new Color(0.6f, 0.7f, 0.9f, 1f)),
    HOME("Home System", "H", new Color(0.3f, 0.8f, 1f, 1f)),
    NURSERY("Stellar Nursery", "*", new Color(1f, 0.85f, 0.45f, 1f)),
    BELT("Asteroid Belt", "o", new Color(0.7f, 0.66f, 0.6f, 1f)),
    GARDEN("Garden Cluster", "G", new Color(0.4f, 0.85f, 0.5f, 1f)),
    FORTRESS("Fortress Worlds", "F", new Color(0.4f, 0.6f, 0.95f, 1f)),
    FRONTIER("Rare Frontier", "R", new Color(0.85f, 0.5f, 0.95f, 1f)),
    RUINS("Ancient Ruins", "A", new Color(0.85f, 0.72f, 0.45f, 1f)),
    NEBULA("Nebula", "~", new Color(0.55f, 0.45f, 0.85f, 1f)),
    VOID("Dead Void", ".", new Color(0.45f, 0.45f, 0.5f, 1f)),
    HAVEN("Pirate Haven", "P", new Color(1f, 0.35f, 0.3f, 1f)),
    CITADEL("Pirate Citadel", "C", new Color(1f, 0.15f, 0.25f, 1f));

    public final String title, icon;
    public final Color color;

    SystemArchetype(String title, String icon, Color color) {
        this.title = title;
        this.icon = icon;
        this.color = color;
    }

    /** Vision multiplier for ships inside a system of this kind (nebulae are murky). */
    public float visionMult() {
        return this == NEBULA ? 0.6f : 1f;
    }
}

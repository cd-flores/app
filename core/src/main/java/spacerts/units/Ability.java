package spacerts.units;

import com.badlogic.gdx.Input;

/** Activated skills. Each has its own hotkey, so a mixed selection can fire exactly the skill you want. */
public enum Ability {
    NONE("-", 0, ""),
    PING("Sensor Ping", Input.Keys.Q, "Q"),
    GHOST("Ghost Drive", Input.Keys.E, "E"),
    TAUNT("Taunt", Input.Keys.T, "T"),
    DASH("Evasive Dash", Input.Keys.Z, "Z"),
    NOVA("Nova Cannon", Input.Keys.N, "N"),
    OVERCHARGE("Overcharge", Input.Keys.O, "O"),
    /** Mothership tier II: jump up to 1000 to a chosen point, then 20 s at scout speed. */
    WARP("Warp Jump", Input.Keys.L, "L");

    public final String title;
    /** libGDX key code and its label. */
    public final int key;
    public final String keyName;

    Ability(String title, int key, String keyName) {
        this.title = title;
        this.key = key;
        this.keyName = keyName;
    }

    /** The skill bound to this key code, or null. */
    public static Ability forKey(int key) {
        for (Ability a : values()) if (a != NONE && a.key == key) return a;
        return null;
    }
}

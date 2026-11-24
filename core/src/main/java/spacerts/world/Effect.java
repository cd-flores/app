package spacerts.world;

import com.badlogic.gdx.graphics.Color;

/** Short-lived visual: beam, blast, expanding ring or floating text. */
public class Effect {
    public enum Kind { BEAM, BLAST, RING, TEXT }

    public final Kind kind;
    public final float x, y, x2, y2, size, life;
    public float ttl;
    public final Color color;
    public final String text;

    public Effect(Kind kind, float x, float y, float x2, float y2, float size, float life, Color color, String text) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
        this.size = size;
        this.life = life;
        this.ttl = life;
        this.color = new Color(color);
        this.text = text;
    }

    public float progress() {
        return 1f - ttl / life;
    }
}

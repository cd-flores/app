package spacerts.world;

/** Temporary area reveal (Sensor Ping) that also detects cloaked ships. */
public class Ping {
    public final Faction owner;
    public final float x, y, r;
    public float ttl;
    /** Revealed sniper position (drawn as a red marker) rather than a Sensor Ping. */
    public boolean sniper;

    public Ping(Faction owner, float x, float y, float r, float ttl) {
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.r = r;
        this.ttl = ttl;
    }
}

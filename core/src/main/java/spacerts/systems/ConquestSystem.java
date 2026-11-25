package spacerts.systems;

import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.StarSystem;
import spacerts.world.World;

/**
 * Hive conquest: works out who holds each hex (a player Claim Beacon = owned, a pirate stronghold = pirate,
 * otherwise grey), pays the player's hex income, and tracks the frontier, the highest danger the player holds.
 * Pirate strength scales with the frontier instead of the clock, so a campaign can run for days.
 */
public class ConquestSystem {
    /** Energy and metal per second for every hex the player owns. */
    public static final float HEX_INCOME = 0.5f;

    private final World w;
    private float timer;
    private int owned, frontier;

    public ConquestSystem(World w) {
        this.w = w;
    }

    public void update(float dt) {
        if (!w.galaxy.hive) return;
        timer -= dt;
        if (timer <= 0) {
            timer = 2f;
            recount();
        }
        if (owned > 0) {
            w.player.energy += owned * HEX_INCOME * dt;
            w.player.metal += owned * HEX_INCOME * dt;
        }
    }

    /** Recomputes every hex's control state (also called right after loading). */
    public void recount() {
        int n = w.galaxy.systems.size();
        boolean[] beacon = new boolean[n], base = new boolean[n];
        int[] pirates = new int[n];
        for (Unit u : w.units) {
            if (u.dead) continue;
            int id = w.systemAt(u.x, u.y).id;
            if (u.cls == ShipClass.BEACON && u.owner == w.player) beacon[id] = true;
            else if (u.cls == ShipClass.PIRATE_BASE) base[id] = true;
            else if (u.owner == w.pirates) pirates[id]++;
        }
        // which hexes have an empire's ships in or next to them: pirates stationed elsewhere sleep
        boolean[] present = new boolean[n];
        for (Unit u : w.units)
            if (!u.dead && u.owner.isMajor() && !u.s.internal) present[w.systemAt(u.x, u.y).id] = true;
        if (active == null || active.length != n) active = new boolean[n];
        for (StarSystem s : w.galaxy.systems) {
            boolean a = present[s.id];
            for (StarSystem nb : s.neighbours) if (nb != null && present[nb.id]) a = true;
            active[s.id] = a;
        }
        StarSystem.Control before;
        owned = 0;
        frontier = 0;
        for (StarSystem s : w.galaxy.systems) {
            before = s.control;
            s.pirateShips = pirates[s.id];
            if (beacon[s.id]) s.control = pirates[s.id] > 0 ? StarSystem.Control.CONTESTED : StarSystem.Control.OWNED;
            else if (base[s.id]) s.control = StarSystem.Control.PIRATE;
            else s.control = StarSystem.Control.GREY;
            if (s.owned()) {
                owned++;
                frontier = Math.max(frontier, s.danger);
            }
            if (before != StarSystem.Control.CONTESTED && s.control == StarSystem.Control.CONTESTED)
                w.notify(w.player, s.name + " is contested - pirates have entered the hex!", World.ALERT);
        }
    }

    public int owned() { return owned; }

    /** Per system: an empire has ships in it or next door. */
    private boolean[] active;

    /**
     * Pirate garrisons and patrols in hexes nobody is near don't need simulating: they sit idle at full health
     * until an empire's ships come within a hex of them (or something shoots them).
     */
    public boolean dormant(Unit u) {
        if (active == null || u.owner != w.pirates || (u.station == null && !u.hasPost)) return false;
        if (u.order != spacerts.units.Order.IDLE || u.target != null || w.time - u.lastHit < 5f) return false;
        return !active[w.systemAt(u.x, u.y).id];
    }

    /** Highest danger among the player's owned hexes (0 when none: just the home hex). */
    public static int frontier(World w) {
        return w.conquest != null ? w.conquest.frontier : 0;
    }

    public static int ownedCount(World w) {
        return w.conquest != null ? w.conquest.owned : 0;
    }

    /** The Claim Beacon standing in s (any owner), or null. */
    public static Unit beaconIn(World w, StarSystem s) {
        for (Unit u : w.units) if (!u.dead && u.cls == ShipClass.BEACON && w.systemAt(u.x, u.y) == s) return u;
        return null;
    }

    /** A pirate stronghold standing in s, or null. */
    public static Unit strongholdIn(World w, StarSystem s) {
        for (Unit u : w.units) if (!u.dead && u.cls == ShipClass.PIRATE_BASE && w.systemAt(u.x, u.y) == s) return u;
        return null;
    }
}

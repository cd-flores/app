package spacerts.systems;

import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Rally Stations (V on the Mothership): every station (Ship Station, Research Hub, Repair Station) of a faction
 * takes a slot on a flat-top hex spiral around its Mothership, the same lattice the Hive galaxy uses, and keeps
 * pace with it. Ring 1 holds 6 stations, ring 2 holds 12, and so on.
 */
public class StationRally {
    /** Distance between neighbouring slots. */
    public static final float SPACING = 150f;
    /** Stations further than this from their slot move faster to catch up with the Mothership. */
    public static final float CATCH_UP_DIST = 300f, CATCH_UP_MULT = 1.5f;
    private static final float SQRT3 = 1.7320508f;
    /** Slot offsets in spiral order (grown on demand). */
    private static final List<float[]> SLOTS = new ArrayList<>();
    private static final List<Integer> SLOT_RING = new ArrayList<>();

    private final World w;
    private final List<Unit> tmp = new ArrayList<>();

    public StationRally(World w) {
        this.w = w;
    }

    /** Offset of slot i (0 = first slot of ring 1) from the Mothership. */
    public static synchronized float[] slot(int i) {
        while (SLOTS.size() <= i) addRing(SLOT_RING.isEmpty() ? 1 : SLOT_RING.get(SLOT_RING.size() - 1) + 1);
        return SLOTS.get(i);
    }

    /** Hex ring of slot i (1-based). */
    public static synchronized int ringOf(int i) {
        slot(i);
        return SLOT_RING.get(i);
    }

    private static void addRing(int k) {
        float lattice = SPACING / SQRT3; // Galaxy: neighbour distance = SQRT3 * lattice size
        int q = Galaxy.DIRS[4][0] * k, r = Galaxy.DIRS[4][1] * k;
        for (int side = 0; side < 6; side++)
            for (int step = 0; step < k; step++) {
                SLOTS.add(new float[]{lattice * 1.5f * q, lattice * SQRT3 * (r + q / 2f)});
                SLOT_RING.add(k);
                q += Galaxy.DIRS[side][0];
                r += Galaxy.DIRS[side][1];
            }
    }

    /** Radius of the outermost ring f's rallied stations use (0 when not rallying). */
    public float formationRadius(Faction f) {
        if (!f.rallyStations || f.mothership == null || f.mothership.dead) return 0;
        int n = 0;
        for (int i = 0, c = w.units.size(); i < c; i++) {
            Unit u = w.units.get(i);
            if (!u.dead && u.owner == f && u.rallied) n++;
        }
        return n == 0 ? 0 : ringOf(n - 1) * SPACING;
    }

    /** Call once per tick before unit behavior: hands out the formation slots. */
    public void update() {
        for (int i = 0, n = w.units.size(); i < n; i++) w.units.get(i).rallied = false;
        for (Faction f : w.factions) {
            if (!f.isMajor() || !f.rallyStations || f.eliminated) continue;
            Unit ms = f.mothership;
            if (ms == null || ms.dead) continue;
            tmp.clear();
            for (int i = 0, n = w.units.size(); i < n; i++) {
                Unit u = w.units.get(i);
                if (!u.dead && u.owner == f && u.cls.isStation()) tmp.add(u);
            }
            // Hub nearest the Mothership, then Ship Stations, then Repair Stations; oldest first
            tmp.sort((a, b) -> a.cls != b.cls ? Integer.compare(order(a.cls), order(b.cls)) : Integer.compare(a.id, b.id));
            for (int i = 0; i < tmp.size(); i++) {
                Unit u = tmp.get(i);
                float[] o = slot(i);
                u.rallied = true;
                u.slotX = w.clampX(ms.x + o[0], ms.y + o[1]);
                u.slotY = w.clampY(ms.x + o[0], ms.y + o[1]);
            }
        }
    }

    private static int order(ShipClass c) {
        return c == ShipClass.HUB ? 0 : c == ShipClass.FACTORY ? 1 : 2;
    }
}

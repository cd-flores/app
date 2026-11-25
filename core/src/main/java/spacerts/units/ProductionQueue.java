package spacerts.units;

import java.util.ArrayList;
import java.util.List;

public class ProductionQueue {
    public static final int MAX = 8;
    public final List<ShipClass> items = new ArrayList<>();
    /** What was actually paid for each queued item: {energy, metal} (prices scale, so cancelling refunds this). */
    public final List<int[]> paid = new ArrayList<>();
    public float progress;

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void add(ShipClass c, int energy, int metal) {
        items.add(c);
        paid.add(new int[]{energy, metal});
    }

    /** Removes item i and returns what was paid for it ({energy, metal}). */
    public int[] removeAt(int i) {
        ShipClass c = items.remove(i);
        int[] p = i < paid.size() ? paid.remove(i) : null;
        return p != null ? p : new int[]{c.s.energy, c.s.metal};
    }

    public int[] paidAt(int i) {
        return i < paid.size() ? paid.get(i) : new int[]{items.get(i).s.energy, items.get(i).s.metal};
    }
}

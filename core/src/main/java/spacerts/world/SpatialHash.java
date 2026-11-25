package spacerts.world;

import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Uniform grid for fast neighbour queries, rebuilt every tick. Cell lists are created on first use and only
 * the cells that held units are cleared, so a huge, mostly empty galaxy costs nothing for its void.
 */
public class SpatialHash {
    private final float cell;
    private final int cols, rows;
    private final List<Unit>[] cells;
    private int[] used = new int[256];
    private int usedCount;

    @SuppressWarnings("unchecked")
    public SpatialHash(float width, float height, float cell) {
        this.cell = cell;
        this.cols = (int) Math.ceil(width / cell) + 1;
        this.rows = (int) Math.ceil(height / cell) + 1;
        this.cells = new List[cols * rows];
    }

    private int cx(float v) {
        int i = (int) (v / cell);
        return i < 0 ? 0 : (i >= cols ? cols - 1 : i);
    }

    private int cy(float v) {
        int i = (int) (v / cell);
        return i < 0 ? 0 : (i >= rows ? rows - 1 : i);
    }

    public void rebuild(List<Unit> units) {
        for (int i = 0; i < usedCount; i++) cells[used[i]].clear();
        usedCount = 0;
        for (int i = 0, n = units.size(); i < n; i++) {
            Unit u = units.get(i);
            if (u.dead) continue;
            int idx = cy(u.y) * cols + cx(u.x);
            List<Unit> l = cells[idx];
            if (l == null) cells[idx] = l = new ArrayList<>();
            if (l.isEmpty()) {
                if (usedCount == used.length) used = java.util.Arrays.copyOf(used, used.length * 2);
                used[usedCount++] = idx;
            }
            l.add(u);
        }
    }

    /** Fills out with candidates near (x,y); callers must still check distance. */
    public void query(float x, float y, float r, List<Unit> out) {
        out.clear();
        int x0 = cx(x - r), x1 = cx(x + r), y0 = cy(y - r), y1 = cy(y + r);
        for (int yy = y0; yy <= y1; yy++)
            for (int xx = x0; xx <= x1; xx++) {
                List<Unit> l = cells[yy * cols + xx];
                if (l != null && !l.isEmpty()) out.addAll(l);
            }
    }
}

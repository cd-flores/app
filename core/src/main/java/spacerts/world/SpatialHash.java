package spacerts.world;

import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;

/** Uniform grid for fast neighbour queries. Rebuilt every tick. */
public class SpatialHash {
    private final float cell;
    private final int cols;
    private final List<Unit>[] cells;

    @SuppressWarnings("unchecked")
    public SpatialHash(float size, float cell) {
        this.cell = cell;
        this.cols = (int) Math.ceil(size / cell) + 1;
        this.cells = new List[cols * cols];
        for (int i = 0; i < cells.length; i++) cells[i] = new ArrayList<>();
    }

    private int c(float v) {
        int i = (int) (v / cell);
        return i < 0 ? 0 : (i >= cols ? cols - 1 : i);
    }

    public void rebuild(List<Unit> units) {
        for (List<Unit> l : cells) l.clear();
        for (Unit u : units) if (!u.dead) cells[c(u.y) * cols + c(u.x)].add(u);
    }

    /** Fills out with candidates near (x,y); callers must still check distance. */
    public void query(float x, float y, float r, List<Unit> out) {
        out.clear();
        int x0 = c(x - r), x1 = c(x + r), y0 = c(y - r), y1 = c(y + r);
        for (int cy = y0; cy <= y1; cy++)
            for (int cx = x0; cx <= x1; cx++)
                out.addAll(cells[cy * cols + cx]);
    }
}

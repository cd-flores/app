package spacerts.world;

import java.util.Arrays;

/** Fog-of-war grid over the whole galaxy: cells currently visible and cells ever explored. */
public class VisionGrid {
    public final int cols, rows;
    public final float cell;
    public final boolean[] visible;
    public boolean[] explored;

    public VisionGrid(float width, float height, float cell) {
        this.cell = cell;
        this.cols = (int) Math.ceil(width / cell);
        this.rows = (int) Math.ceil(height / cell);
        this.visible = new boolean[cols * rows];
        this.explored = new boolean[cols * rows];
    }

    public void beginFrame() {
        Arrays.fill(visible, false);
    }

    public void reveal(float x, float y, float r) {
        int c0 = clampC((int) ((x - r) / cell)), c1 = clampC((int) ((x + r) / cell));
        int r0 = clampR((int) ((y - r) / cell)), r1 = clampR((int) ((y + r) / cell));
        float rr = (r + cell * 0.35f) * (r + cell * 0.35f);
        for (int cy = r0; cy <= r1; cy++) {
            float dy = (cy + 0.5f) * cell - y;
            for (int cx = c0; cx <= c1; cx++) {
                float dx = (cx + 0.5f) * cell - x;
                if (dx * dx + dy * dy <= rr) {
                    int i = cy * cols + cx;
                    visible[i] = true;
                    explored[i] = true;
                }
            }
        }
    }

    private int clampC(int c) {
        return c < 0 ? 0 : (c >= cols ? cols - 1 : c);
    }

    private int clampR(int c) {
        return c < 0 ? 0 : (c >= rows ? rows - 1 : c);
    }

    public boolean isVisible(float x, float y) {
        int cx = (int) (x / cell), cy = (int) (y / cell);
        if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) return false;
        return visible[cy * cols + cx];
    }

    public boolean isExplored(float x, float y) {
        int cx = (int) (x / cell), cy = (int) (y / cell);
        if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) return false;
        return explored[cy * cols + cx];
    }
}

package spacerts.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.Random;

/**
 * The Hive's background: a slowly spinning spiral galaxy made of glowing dots, drawn in screen space behind
 * the hex systems. Four logarithmic arms of blue-white stars around a warm core, pink/violet dust lanes between
 * them, differential rotation (the core turns faster, so the arms wind), soft additive halos, and fading orbit
 * trails behind the brightest stars. It runs on real time, so it keeps turning while the game is paused.
 */
public class GalaxyBackdrop {
    private static final int N = 6000;
    private static final float TILT = 0.58f, ROLL = 0.35f; // seen slightly from above, rolled a little
    private final float[] rad = new float[N], ang = new float[N], size = new float[N], bright = new float[N];
    private final float[] cr = new float[N], cg = new float[N], cb = new float[N];
    private final boolean[] trail = new boolean[N];
    private float clock;
    /** Can be switched off from the pause menu on slow machines. */
    public static boolean enabled = true;

    public GalaxyBackdrop(long seed) {
        Random r = new Random(seed ^ 0x5EED_6A1AL);
        for (int i = 0; i < N; i++) {
            float roll = r.nextFloat();
            if (roll < 0.18f) { // bulge: dense, warm
                rad[i] = (float) Math.abs(r.nextGaussian()) * 0.11f;
                ang[i] = r.nextFloat() * 6.2832f;
                colour(i, 1f, 0.9f - r.nextFloat() * 0.12f, 0.7f - r.nextFloat() * 0.2f);
                size[i] = 0.8f + r.nextFloat() * 1.3f;
                bright[i] = 0.55f + r.nextFloat() * 0.45f;
            } else if (roll < 0.82f) { // spiral arms: blue-white
                int arm = r.nextInt(4);
                float t = (float) Math.pow(r.nextFloat(), 0.75);
                rad[i] = 0.07f + t * 0.93f;
                float wind = (float) (Math.log(rad[i] / 0.07f) / 0.32f); // logarithmic spiral, pitch ~ 18 deg
                ang[i] = arm * 1.5708f + wind + (float) r.nextGaussian() * (0.16f + 0.1f * t);
                float warm = r.nextFloat() < 0.15f ? 1f : 0f;
                colour(i, 0.72f + 0.28f * warm, 0.82f + 0.1f * warm, 1f - 0.25f * warm);
                size[i] = 0.6f + r.nextFloat() * (r.nextFloat() < 0.04f ? 2.6f : 1.2f);
                bright[i] = 0.25f + r.nextFloat() * 0.6f * (1.1f - t * 0.5f);
            } else { // dust and nebulae between the arms: pink / violet, dim and soft
                int arm = r.nextInt(4);
                float t = r.nextFloat();
                rad[i] = 0.12f + t * 0.8f;
                float wind = (float) (Math.log(rad[i] / 0.07f) / 0.32f);
                ang[i] = arm * 1.5708f + 0.78f + wind + (float) r.nextGaussian() * 0.22f;
                if (r.nextBoolean()) colour(i, 0.95f, 0.45f, 0.75f);
                else colour(i, 0.6f, 0.45f, 0.95f);
                size[i] = 1.5f + r.nextFloat() * 3f;
                bright[i] = 0.08f + r.nextFloat() * 0.18f;
            }
            trail[i] = bright[i] > 0.6f && r.nextFloat() < 0.18f; // about 5% of all stars leave a glowing trail
        }
    }

    private void colour(int i, float r, float g, float b) {
        cr[i] = r;
        cg[i] = g;
        cb[i] = b;
    }

    /** Angular speed (rad/s) at normalised radius r: the core turns faster than the rim. */
    private static float omega(float r) {
        return 0.004f + 0.014f / (1f + r * 6f);
    }

    /**
     * Draws in screen space (projection must already be the HUD camera). camX/camY: world camera position,
     * galaxyW/H: the galaxy's world extent (for parallax), zoom: camera zoom.
     */
    public void draw(ShapeRenderer sr, float W, float H, float camX, float camY, float galaxyW, float galaxyH, float zoom, float delta) {
        if (!enabled) return;
        clock += Math.min(delta, 0.1f);
        // the galaxy sits behind the hive: moves 3% with the camera, grows a little as you zoom in
        float R = Math.max(W, H) * 0.62f * (1.12f - 0.03f * Math.min(zoom, 4f));
        float par = 0.015f * Math.min(1f, 80000f / Math.max(galaxyW, 1f)); // same on-screen drift whatever the galaxy size
        float ox = W / 2 - (camX - galaxyW / 2) * par;
        float oy = H / 2 - (camY - galaxyH / 2) * par;
        float cRoll = (float) Math.cos(ROLL), sRoll = (float) Math.sin(ROLL);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE); // additive: overlapping light glows
        sr.begin(ShapeRenderer.ShapeType.Filled);
        // diffuse core glow
        for (int k = 5; k >= 1; k--) {
            sr.setColor(1f, 0.82f, 0.6f, 0.018f * k);
            ellipse(sr, ox, oy, R * 0.05f * (6 - k) * 1.3f, cRoll, sRoll);
        }
        for (int i = 0; i < N; i++) {
            float a = ang[i] + clock * omega(rad[i]);
            float px = ox + point(rad[i] * R, a, cRoll, sRoll, true);
            float py = oy + point(rad[i] * R, a, cRoll, sRoll, false);
            if (px < -20 || py < -20 || px > W + 20 || py > H + 20) continue;
            float s = size[i], b = bright[i];
            if (trail[i]) { // a short fading arc behind the star along its orbit
                float step = 0.012f + 0.02f / (1f + rad[i] * 4f);
                float lx = px, ly = py;
                for (int k = 1; k <= 4; k++) {
                    float ta = a - step * k;
                    float tx = ox + point(rad[i] * R, ta, cRoll, sRoll, true), ty = oy + point(rad[i] * R, ta, cRoll, sRoll, false);
                    sr.setColor(cr[i], cg[i], cb[i], b * 0.3f * (1f - k / 5f));
                    sr.rectLine(lx, ly, tx, ty, Math.max(1f, s * 0.8f));
                    lx = tx;
                    ly = ty;
                }
            }
            if (b > 0.45f || s > 1.8f) { // soft halo
                sr.setColor(cr[i], cg[i], cb[i], b * 0.07f);
                sr.circle(px, py, s * 4f, 8);
            }
            sr.setColor(cr[i], cg[i], cb[i], b);
            if (s < 1.4f) sr.rect(px - s / 2, py - s / 2, s, s);
            else sr.circle(px, py, s * 0.6f, 6);
        }
        sr.end();
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Screen offset of a point at radius r, angle a on the tilted, rolled disc. */
    private static float point(float r, float a, float cRoll, float sRoll, boolean x) {
        float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r * TILT;
        return x ? dx * cRoll - dy * sRoll : dx * sRoll + dy * cRoll;
    }

    private static void ellipse(ShapeRenderer sr, float x, float y, float r, float cRoll, float sRoll) {
        int seg = 28;
        for (int i = 0; i < seg; i++) {
            float a0 = (float) (Math.PI * 2 * i / seg), a1 = (float) (Math.PI * 2 * (i + 1) / seg);
            sr.triangle(x, y, x + point(r, a0, cRoll, sRoll, true), y + point(r, a0, cRoll, sRoll, false),
                    x + point(r, a1, cRoll, sRoll, true), y + point(r, a1, cRoll, sRoll, false));
        }
    }
}

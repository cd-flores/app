package spacerts.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
import spacerts.input.GameInput;
import spacerts.screens.GameScreen;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Effect;
import spacerts.world.Ping;
import spacerts.world.PlanetType;
import spacerts.world.StarSystem;
import spacerts.world.VisionGrid;
import spacerts.world.World;

import java.util.Random;

/** Draws the galaxy, units, effects and fog of war with ShapeRenderer. */
public class WorldRenderer {
    private final GameScreen gs;
    private final float[] bgX, bgY, bgS, bgB;
    private final Color tmp = new Color();
    private final Vector3 v3 = new Vector3();
    private float vx0, vy0, vx1, vy1, zoom;

    private static final Color SEL = new Color(0.35f, 1f, 0.45f, 1f);
    private static final Color HP_BG = new Color(0.15f, 0.05f, 0.05f, 0.85f);
    private static final Color SHIELD_C = new Color(0.4f, 0.75f, 1f, 1f);
    private static final Color DEFENDED_C = new Color(1f, 0.3f, 0.25f, 1f);

    public WorldRenderer(GameScreen gs) {
        this.gs = gs;
        Random r = new Random(1234);
        int n = 420;
        bgX = new float[n];
        bgY = new float[n];
        bgS = new float[n];
        bgB = new float[n];
        for (int i = 0; i < n; i++) {
            bgX[i] = r.nextFloat();
            bgY[i] = r.nextFloat();
            bgS[i] = r.nextFloat();
            bgB[i] = 0.25f + r.nextFloat() * 0.6f;
        }
    }

    /** The Hive's spinning galaxy background (created on first use). */
    private GalaxyBackdrop backdrop;

    public void render() {
        World w = gs.world;
        OrthographicCamera cam = gs.cam;
        ShapeRenderer sr = gs.game.shapes;
        zoom = cam.zoom;
        float hw = cam.viewportWidth * zoom / 2, hh = cam.viewportHeight * zoom / 2;
        vx0 = cam.position.x - hw - 150;
        vx1 = cam.position.x + hw + 150;
        vy0 = cam.position.y - hh - 150;
        vy1 = cam.position.y + hh + 150;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // parallax background in screen space
        float W = Gdx.graphics.getWidth(), H = Gdx.graphics.getHeight();
        sr.setProjectionMatrix(gs.hudCam.combined);
        if (w.galaxy.hive) {
            if (backdrop == null) backdrop = new GalaxyBackdrop(w.seed);
            backdrop.draw(sr, W, H, cam.position.x, cam.position.y, w.galaxy.width, w.galaxy.height, zoom, Gdx.graphics.getDeltaTime());
            if (gs.showGalaxy) return; // the galaxy map is drawn by the HUD over the backdrop alone
        }
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < bgX.length; i++) {
            float depth = 0.02f + bgS[i] * 0.06f;
            float x = mod(bgX[i] * W * 3 - cam.position.x * depth, W);
            float y = mod(bgY[i] * H * 3 - cam.position.y * depth, H);
            float s = 1f + bgS[i] * 1.6f;
            sr.setColor(bgB[i], bgB[i], bgB[i] + 0.08f, 1f);
            sr.rect(x, y, s, s);
        }
        sr.end();

        sr.setProjectionMatrix(cam.combined);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        // map borders: the classic square, or every hex outline in view
        float lw = 3 * zoom;
        for (StarSystem s : w.galaxy.systems) {
            if (s.maxX() < vx0 || s.minX() > vx1 || s.maxY() < vy0 || s.minY() > vy1) continue;
            boolean here = s == w.systemAt(cam.position.x, cam.position.y);
            sr.setColor(0.15f, 0.25f, 0.45f, here ? 0.6f : 0.3f);
            if (!s.hex) {
                float x0 = s.minX(), y0 = s.minY(), x1 = s.maxX(), y1 = s.maxY();
                sr.rect(x0 - lw, y0 - lw, x1 - x0 + 2 * lw, lw);
                sr.rect(x0 - lw, y1, x1 - x0 + 2 * lw, lw);
                sr.rect(x0 - lw, y0, lw, y1 - y0);
                sr.rect(x1, y0, lw, y1 - y0);
            } else {
                for (int k = 0; k < 6; k++) sr.rectLine(s.cornerX(k), s.cornerY(k), s.cornerX(k + 1), s.cornerY(k + 1), lw);
            }
        }

        VisionGrid vg = w.player.vision;
        for (Body b : w.bodies) {
            if (b.dead || !inView(b.x, b.y, b.radius * 2.5f) || !vg.isExplored(b.x, b.y)) continue;
            drawBody(sr, w, b);
        }
        // colony buoys: small diamonds in the owner's colour, where the player can see them
        for (Body b : w.bodies) {
            if (b.buoyRings <= 0 || !b.colonized()) continue;
            Color bc = b.colony.owner.color;
            float pulse = 0.65f + 0.35f * (float) Math.sin(w.time * 3 + b.x);
            for (int k = 1; k <= b.buoyRings; k++)
                for (int j = 0; j < Body.BUOYS_PER_RING; j++) {
                    b.buoy(k, j, buoyPos);
                    if (!inView(buoyPos[0], buoyPos[1], 20) || !vg.isVisible(buoyPos[0], buoyPos[1])) continue;
                    sr.setColor(bc.r, bc.g, bc.b, 0.9f * pulse);
                    sr.circle(buoyPos[0], buoyPos[1], 7f, 4);
                    sr.setColor(1f, 1f, 1f, 0.8f * pulse);
                    sr.circle(buoyPos[0], buoyPos[1], 2.5f, 4);
                }
        }
        // harvest beams
        for (Unit u : w.units) {
            if (u.dead || !u.s.harvester || u.order != Order.HARVEST || u.harvestState != 1 || u.body == null) continue;
            if (!inView(u.x, u.y, 50) || !w.canSee(w.player, u)) continue;
            float flick = 0.35f + 0.25f * (float) Math.sin(w.time * 20 + u.id);
            Color c = u.body.kind == Body.Kind.STAR ? u.body.color : Color.LIGHT_GRAY;
            sr.setColor(c.r, c.g, c.b, flick);
            float dx = u.body.x - u.x, dy = u.body.y - u.y, d = (float) Math.hypot(dx, dy);
            float ex = u.body.x - dx / d * u.body.radius * 0.9f, ey = u.body.y - dy / d * u.body.radius * 0.9f;
            sr.rectLine(u.x, u.y, ex, ey, 2f * Math.max(1, zoom * 0.7f));
        }
        // pings
        for (Ping p : w.pings) {
            if (p.owner != w.player) continue;
            if (p.sniper) {
                // revealed sniper: pulsing red crosshair
                float a = Math.min(1f, p.ttl) * (0.55f + 0.35f * (float) Math.sin(w.time * 10));
                float cr = 34 * Math.max(1, zoom * 0.7f);
                sr.setColor(1f, 0.25f, 0.2f, a);
                ring(sr, p.x, p.y, cr, 2f * Math.max(1, zoom * 0.7f), 24);
                float xw = 1.5f * Math.max(1, zoom * 0.7f);
                sr.rectLine(p.x - cr * 1.5f, p.y, p.x - cr * 0.5f, p.y, xw);
                sr.rectLine(p.x + cr * 0.5f, p.y, p.x + cr * 1.5f, p.y, xw);
                sr.rectLine(p.x, p.y - cr * 1.5f, p.x, p.y - cr * 0.5f, xw);
                sr.rectLine(p.x, p.y + cr * 0.5f, p.x, p.y + cr * 1.5f, xw);
                continue;
            }
            sr.setColor(p.owner.color.r, p.owner.color.g, p.owner.color.b, 0.06f + 0.04f * (float) Math.sin(w.time * 4));
            ring(sr, p.x, p.y, p.r, 4 * zoom, 64);
        }

        GameInput in = gs.input;
        // construction sites of the player's harvesters
        for (Unit u : w.units) {
            if (u.dead || u.owner != w.player || u.buildCls == null) continue;
            ShipClass c = u.buildCls;
            if ((c.needsSite() || c.isExtractor()) && inView(u.buildX, u.buildY, c.s.radius + 20)) {
                Color oc = u.owner.color;
                sr.setColor(oc.r, oc.g, oc.b, 0.12f);
                sr.circle(u.buildX, u.buildY, c.s.radius * Math.max(0.05f, u.work), segs(c.s.radius));
                sr.setColor(oc.r, oc.g, oc.b, 0.5f);
                dashedRing(sr, u.buildX, u.buildY, c.s.radius, 1.5f * zoom, 24, w.time * 0.5f);
            } else if (c.isPlanetUpgrade() && u.buildBody != null && inView(u.buildX, u.buildY, u.buildBody.radius + 80)) {
                sr.setColor(1f, 0.85f, 0.4f, 0.4f);
                dashedRing(sr, u.buildBody.x, u.buildBody.y, u.buildBody.radius + 40, 1.5f * zoom, 32, w.time * 0.5f);
            }
        }
        // units
        for (Unit u : w.units) {
            if (u.dead || !inView(u.x, u.y, u.radius + 20)) continue;
            if (!w.canSee(w.player, u)) continue;
            drawUnit(sr, w, u);
            if (u.burnTime > 0) { // Pyre fire: flickering embers on the hull
                for (int i = 0; i < 3; i++) {
                    float a = u.id * 2.1f + i * 2.09f + w.time * 5f;
                    float fr = u.radius * (0.35f + 0.15f * (float) Math.sin(w.time * 17 + i * 3));
                    sr.setColor(1f, 0.45f + 0.25f * i / 2f, 0.1f, 0.75f);
                    sr.circle(u.x + (float) Math.cos(a) * u.radius * 0.5f, u.y + (float) Math.sin(a) * u.radius * 0.5f, fr, 6);
                }
            }
        }
        // rally flag
        if (w.player.hasFlag) drawFlag(sr, w.player.flagX, w.player.flagY, w.player.color, 1f);
        // placement preview at the cursor
        if (in.isPlacing()) {
            float[] cp = in.cursorWorld();
            if (in.placingFlag) drawFlag(sr, cp[0], cp[1], w.player.color, 0.5f);
            else if (in.placing.needsSite()) {
                boolean ok = w.construction.siteBlocker(w.player, cp[0], cp[1], in.placing.s.radius) == null;
                Color pc = ok ? SEL : DEFENDED_C;
                sr.setColor(pc.r, pc.g, pc.b, 0.18f);
                sr.circle(cp[0], cp[1], in.placing.s.radius, segs(in.placing.s.radius));
                sr.setColor(pc.r, pc.g, pc.b, 0.8f);
                ring(sr, cp[0], cp[1], in.placing.s.radius, 1.5f * zoom, 32);
                // preview what it covers: repair radius, weapon range or vision
                float reach = in.placing.s.healRange > 0 ? in.placing.s.healRange : in.placing.s.range > 0 ? in.placing.s.range
                        : in.placing == ShipClass.SENSOR || in.placing == ShipClass.BEACON ? in.placing.s.vision : 0;
                if (reach > 0) {
                    sr.setColor(in.placing.s.healRange > 0 ? 0.4f : pc.r, in.placing.s.healRange > 0 ? 1f : pc.g, in.placing.s.healRange > 0 ? 0.5f : pc.b, 0.35f);
                    ring(sr, cp[0], cp[1], reach, 1.2f * zoom, 64);
                }
            } else if (in.placing.isExtractor()) {
                // highlight the star / asteroid under the cursor: green if it can take one, red if not
                Body b = w.bodyAt(cp[0], cp[1], 30 * zoom);
                Unit h = in.activeBuilder();
                if (b != null && h != null && w.player.vision.isExplored(b.x, b.y)) {
                    boolean ok = w.construction.blocker(h, in.placing, b, b.x, b.y) == null;
                    Color pc = ok ? SEL : DEFENDED_C;
                    sr.setColor(pc.r, pc.g, pc.b, 0.6f + 0.3f * (float) Math.sin(w.time * 5));
                    ring(sr, b.x, b.y, b.radius + 14, 2f * zoom, segs(b.radius + 14));
                    if (in.placing == ShipClass.MINER && b.kind == Body.Kind.ASTEROID) {
                        sr.setColor(pc.r, pc.g, pc.b, 0.15f);
                        dashedRing(sr, b.x, b.y, spacerts.systems.ConstructionSystem.MINER_REACH, 1.2f * zoom, 40, w.time * 0.3f);
                    }
                }
            } else if (in.placing.isPlanetUpgrade()) {
                for (Unit c : w.player.colonies) {
                    if (c.dead || c.home == null) continue;
                    boolean full = w.construction.pointsUsed(w.player, c.home) >= spacerts.world.Body.MAX_DEFENSE;
                    Color pc = full ? DEFENDED_C : SEL;
                    sr.setColor(pc.r, pc.g, pc.b, 0.5f + 0.3f * (float) Math.sin(w.time * 5));
                    ring(sr, c.x, c.y, c.home.radius + 30, 2f * zoom, 40);
                }
            }
        }
        // selection, order lines, health bars
        for (Unit u : in.selected) {
            if (u.dead) continue;
            sr.setColor(SEL.r, SEL.g, SEL.b, 0.9f);
            ring(sr, u.x, u.y, u.radius + 5 * Math.max(1, zoom * 0.6f), 1.6f * zoom, 28);
            if (u.order == Order.MOVE || u.order == Order.ATTACK_MOVE || u.order == Order.COLONIZE || u.order == Order.SURVEY || u.order == Order.BUILD) {
                Color c = u.order == Order.ATTACK_MOVE ? DEFENDED_C : SEL;
                sr.setColor(c.r, c.g, c.b, 0.22f);
                sr.rectLine(u.x, u.y, u.tx, u.ty, 1.2f * zoom);
            }
            if (u.order == Order.ATTACK && u.target != null && !u.target.dead) {
                sr.setColor(1f, 0.3f, 0.25f, 0.3f);
                sr.rectLine(u.x, u.y, u.target.x, u.target.y, 1.2f * zoom);
            }
            if (u.queue != null && u.hasRally) {
                sr.setColor(SEL.r, SEL.g, SEL.b, 0.3f);
                sr.rectLine(u.x, u.y, u.rallyX, u.rallyY, 1.2f * zoom);
                sr.setColor(SEL);
                sr.rect(u.rallyX - 3 * zoom, u.rallyY - 3 * zoom, 6 * zoom, 6 * zoom);
            }
            if (u.cls == ShipClass.DEFENDER && u.owner.mothership != null && !u.owner.mothership.dead) { // its orbit
                Unit ms = u.owner.mothership;
                sr.setColor(SEL.r, SEL.g, SEL.b, 0.12f);
                float orb = w.behavior.defenderOrbit(u.owner);
                ring(sr, ms.x, ms.y, orb, 1.2f * zoom, segs(orb));
            }
            if (u.formation != 0) { // tactic formation: its slot while forming up / marching
                spacerts.systems.TacticsSystem.Formation fm = w.tactics.formationOf(u);
                if (fm != null && fm.state != spacerts.systems.TacticsSystem.State.ENGAGED)
                    for (spacerts.systems.TacticsSystem.Member m : fm.members)
                        if (m.u == u) {
                            sr.setColor(1f, 0.85f, 0.45f, 0.35f);
                            ring(sr, fm.slotX(m), fm.slotY(m), u.radius + 4, 1.2f * zoom, 16);
                        }
            }
            if (u.rallied) { // its slot in the Mothership's formation
                sr.setColor(SEL.r, SEL.g, SEL.b, 0.25f);
                sr.rectLine(u.x, u.y, u.slotX, u.slotY, 1.2f * zoom);
            }
            if (u.s.aura > 0) {
                sr.setColor(SHIELD_C.r, SHIELD_C.g, SHIELD_C.b, 0.15f);
                ring(sr, u.x, u.y, u.s.aura, 1.5f * zoom, 40);
            }
            if (u.armed() && u.s.range > 0) {
                sr.setColor(1f, 1f, 1f, 0.05f);
                ring(sr, u.x, u.y, u.range(), 1.2f * zoom, 48);
            }
        }
        if (in.inspectUnit != null && !in.inspectUnit.dead) {
            Unit u = in.inspectUnit;
            sr.setColor(1f, 0.45f, 0.35f, 0.9f);
            ring(sr, u.x, u.y, u.radius + 5 * Math.max(1, zoom * 0.6f), 1.6f * zoom, 28);
        }
        if (in.inspectBody != null) {
            Body b = in.inspectBody;
            sr.setColor(1f, 1f, 1f, 0.5f);
            ring(sr, b.x, b.y, b.radius + 8, 1.5f * zoom, 40);
        }
        for (Unit u : w.units) {
            if (u.dead || !inView(u.x, u.y, u.radius + 20) || !w.canSee(w.player, u) || u.cls == ShipClass.DRONE) continue;
            boolean damaged = u.hp < u.maxHp() - 0.5f || (u.s.shield > 0 && u.shield < u.maxShield() - 0.5f);
            if (damaged || in.selected.contains(u) || u == in.inspectUnit) healthBar(sr, u);
        }

        // effects
        for (Effect e : w.effects) {
            if (!inView(e.x, e.y, e.size + 600)) continue;
            float a = e.ttl / e.life;
            switch (e.kind) {
                case BEAM:
                    sr.setColor(e.color.r, e.color.g, e.color.b, a);
                    sr.rectLine(e.x, e.y, e.x2, e.y2, e.size * Math.max(1f, zoom * 0.8f));
                    break;
                case BLAST: {
                    float p = e.progress();
                    tmp.set(e.color).lerp(Color.WHITE, 0.5f * (1 - p));
                    sr.setColor(tmp.r, tmp.g, tmp.b, (1 - p) * 0.75f);
                    sr.circle(e.x, e.y, e.size * (0.3f + 0.7f * p), 24);
                    sr.setColor(1f, 0.95f, 0.8f, (1 - p) * 0.6f);
                    sr.circle(e.x, e.y, e.size * 0.35f * (1 - p), 16);
                    break;
                }
                case RING:
                    sr.setColor(e.color.r, e.color.g, e.color.b, a * 0.8f);
                    ring(sr, e.x, e.y, Math.max(2, e.size * e.progress()), 2.5f * zoom, 48);
                    break;
                default:
                    break;
            }
        }

        // fog of war
        int c0 = Math.max(0, (int) (vx0 / vg.cell)), c1 = Math.min(vg.cols - 1, (int) (vx1 / vg.cell));
        int r0 = Math.max(0, (int) (vy0 / vg.cell)), r1 = Math.min(vg.cols - 1, (int) (vy1 / vg.cell));
        for (int cy = r0; cy <= r1; cy++) {
            for (int cx = c0; cx <= c1; cx++) {
                int i = cy * vg.cols + cx;
                if (vg.visible[i]) continue;
                if (!vg.explored[i]) sr.setColor(0.0f, 0.0f, 0.012f, 0.97f);
                else sr.setColor(0.0f, 0.0f, 0.03f, 0.5f);
                sr.rect(cx * vg.cell, cy * vg.cell, vg.cell + 0.5f, vg.cell + 0.5f);
            }
        }

        // alert marker
        if (w.time - w.alertTime < 6) {
            float p = ((w.time - w.alertTime) * 1.5f) % 1f;
            sr.setColor(1f, 0.3f, 0.25f, 1 - p);
            ring(sr, w.alertX, w.alertY, 60 + 200 * p, 3 * zoom, 40);
        }
        sr.end();

        // selection box (screen space)
        if (in.dragging && (Math.abs(in.curX - in.dragSX) > 5 || Math.abs(in.curY - in.dragSY) > 5)) {
            sr.setProjectionMatrix(gs.hudCam.combined);
            sr.begin(ShapeRenderer.ShapeType.Filled);
            float x0 = Math.min(in.dragSX, in.curX), x1 = Math.max(in.dragSX, in.curX);
            float y0 = H - Math.max(in.dragSY, in.curY), y1 = H - Math.min(in.dragSY, in.curY);
            sr.setColor(0.35f, 1f, 0.45f, 0.08f);
            sr.rect(x0, y0, x1 - x0, y1 - y0);
            sr.setColor(0.35f, 1f, 0.45f, 0.8f);
            sr.rect(x0, y0, x1 - x0, 1);
            sr.rect(x0, y1, x1 - x0, 1);
            sr.rect(x0, y0, 1, y1 - y0);
            sr.rect(x1, y0, 1, y1 - y0);
            sr.end();
        }

        labels(w);
    }

    /** Pole and waving pennant; size stays readable at any zoom. */
    private void drawFlag(ShapeRenderer sr, float x, float y, Color c, float alpha) {
        float s = Math.max(1f, zoom);
        float h = 46 * s, fw = 26 * s, fh = 15 * s;
        float wave = (float) Math.sin(gs.world.time * 4) * 3 * s;
        sr.setColor(0f, 0f, 0f, 0.35f * alpha);
        sr.ellipse(x - 8 * s, y - 3 * s, 16 * s, 6 * s);
        sr.setColor(0.9f, 0.9f, 0.95f, alpha);
        sr.rectLine(x, y, x, y + h, 2.2f * s);
        sr.setColor(c.r, c.g, c.b, alpha);
        sr.triangle(x, y + h, x, y + h - fh, x + fw, y + h - fh / 2 + wave);
    }

    private static float mod(float a, float m) {
        return ((a % m) + m) % m;
    }

    private boolean inView(float x, float y, float r) {
        return x + r > vx0 && x - r < vx1 && y + r > vy0 && y - r < vy1;
    }

    private int segs(float r) {
        return Math.max(10, Math.min(72, (int) (r / zoom / 2.5f)));
    }

    private void ring(ShapeRenderer sr, float x, float y, float r, float width, int n) {
        float px = x + r, py = y;
        for (int i = 1; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            float nx = x + (float) Math.cos(a) * r, ny = y + (float) Math.sin(a) * r;
            sr.rectLine(px, py, nx, ny, width);
            px = nx;
            py = ny;
        }
    }

    private void dashedRing(ShapeRenderer sr, float x, float y, float r, float width, int n, float rot) {
        for (int i = 0; i < n; i += 2) {
            double a0 = Math.PI * 2 * i / n + rot, a1 = Math.PI * 2 * (i + 1) / n + rot;
            sr.rectLine(x + (float) Math.cos(a0) * r, y + (float) Math.sin(a0) * r,
                    x + (float) Math.cos(a1) * r, y + (float) Math.sin(a1) * r, width);
        }
    }

    // ------------------------------------------------------------------ bodies

    private void drawBody(ShapeRenderer sr, World w, Body b) {
        Color c = b.color;
        switch (b.kind) {
            case STAR: {
                if (b.burntOut()) { // a cold cinder
                    float rr = b.radius * 0.4f;
                    sr.setColor(c.r * 0.35f, c.g * 0.25f, c.b * 0.25f, 1f);
                    sr.circle(b.x, b.y, rr, segs(rr));
                    break;
                }
                float scale = 0.4f + 0.6f * b.starLeft(); // shrinks as its deposit drains
                float pulse = scale * (1f + 0.03f * (float) Math.sin(w.time * 2 + b.x));
                sr.setColor(c.r, c.g, c.b, 0.05f);
                sr.circle(b.x, b.y, b.radius * 2.6f * pulse, segs(b.radius * 2.6f));
                sr.setColor(c.r, c.g, c.b, 0.1f);
                sr.circle(b.x, b.y, b.radius * 1.7f * pulse, segs(b.radius * 1.7f));
                float sr0 = b.radius * scale;
                sr.setColor(c.r, c.g, c.b, 0.3f);
                sr.circle(b.x, b.y, sr0 * 1.2f, segs(sr0 * 1.2f));
                sr.setColor(c);
                sr.circle(b.x, b.y, sr0, segs(sr0));
                sr.setColor(1f, 1f, 0.95f, 0.7f);
                sr.circle(b.x, b.y, sr0 * 0.6f, segs(sr0));
                break;
            }
            case ASTEROID: {
                float r = b.radius * (0.45f + 0.55f * (b.maxMetal > 0 ? (float) (b.metal / b.maxMetal) : 1));
                sr.setColor(c.r * 0.7f, c.g * 0.7f, c.b * 0.7f, 1f);
                sr.circle(b.x, b.y, r, 9);
                sr.setColor(c);
                sr.circle(b.x - r * 0.15f, b.y + r * 0.15f, r * 0.8f, 9);
                sr.setColor(c.r * 0.6f, c.g * 0.6f, c.b * 0.6f, 1f);
                sr.circle(b.x + r * 0.25f, b.y - r * 0.1f, r * 0.25f, 6);
                if (b.giant) { // craters and glinting ore veins on the giants
                    for (int i = 0; i < 5; i++) {
                        double a = i * 1.3 + b.x * 0.01;
                        float cx = b.x + (float) Math.cos(a) * r * 0.5f, cy = b.y + (float) Math.sin(a) * r * 0.5f;
                        sr.setColor(c.r * 0.5f, c.g * 0.5f, c.b * 0.5f, 1f);
                        sr.circle(cx, cy, r * (0.1f + 0.04f * i), 10);
                    }
                    float glint = 0.45f + 0.35f * (float) Math.sin(w.time * 1.5f + b.y);
                    sr.setColor(1f, 0.8f, 0.35f, glint);
                    for (int i = 0; i < 3; i++) {
                        double a = i * 2.1 + 0.4;
                        sr.rectLine(b.x, b.y, b.x + (float) Math.cos(a) * r * 0.7f, b.y + (float) Math.sin(a) * r * 0.7f, Math.max(2f, r * 0.05f));
                    }
                }
                break;
            }
            case GATE: { // jump gate: a spinning double ring with an arrow toward the system it leads to
                float r = b.radius, pulse = 0.5f + 0.5f * (float) Math.sin(w.time * 2.5f + b.x * 0.01f);
                sr.setColor(c.r, c.g, c.b, 0.06f + 0.05f * pulse);
                sr.circle(b.x, b.y, r * 2.4f, segs(r * 2.4f));
                sr.setColor(0.05f, 0.12f, 0.2f, 0.9f);
                sr.circle(b.x, b.y, r * 0.8f, segs(r));
                sr.setColor(c.r, c.g, c.b, 0.85f);
                ring(sr, b.x, b.y, r, 5f, 36);
                sr.setColor(c.r, c.g, c.b, 0.4f + 0.4f * pulse);
                ring(sr, b.x, b.y, r * 0.62f, 2.5f, 24);
                for (int i = 0; i < 6; i++) { // rotating struts
                    double a = w.time * 0.6 + i * Math.PI / 3;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    sr.rectLine(b.x + ca * r * 0.62f, b.y + sa * r * 0.62f, b.x + ca * r, b.y + sa * r, 2.5f);
                }
                if (b.side >= 0) {
                    double a = Math.PI / 3 * b.side + Math.PI / 6;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    float tipX = b.x + ca * r * 2.1f, tipY = b.y + sa * r * 2.1f;
                    float bx = b.x + ca * r * 1.4f, by = b.y + sa * r * 1.4f;
                    sr.setColor(c.r, c.g, c.b, 0.7f);
                    sr.triangle(tipX, tipY, bx - sa * r * 0.35f, by + ca * r * 0.35f, bx + sa * r * 0.35f, by - ca * r * 0.35f);
                }
                break;
            }
            case WRECK: { // a scatter of grey hull shards that fades as it drifts away
                float a = Math.min(1f, b.life / 60f) * 0.9f;
                float r = b.radius * (0.5f + 0.5f * (b.maxMetal > 0 ? (float) (b.metal / b.maxMetal) : 1));
                for (int i = 0; i < 5; i++) {
                    float ang = i * 1.257f + b.x * 0.013f + w.time * 0.05f * (i % 2 == 0 ? 1 : -1);
                    float d = r * (0.25f + 0.15f * i);
                    float px = b.x + (float) Math.cos(ang) * d, py = b.y + (float) Math.sin(ang) * d;
                    float s = r * (0.45f - 0.05f * i);
                    sr.setColor(0.55f, 0.55f, 0.58f, a);
                    sr.triangle(px - s, py - s * 0.4f, px + s * 0.8f, py - s * 0.2f, px, py + s * 0.6f);
                }
                sr.setColor(1f, 0.55f, 0.2f, a * (0.4f + 0.3f * (float) Math.sin(w.time * 3 + b.y))); // smouldering core
                sr.circle(b.x, b.y, r * 0.2f, 6);
                break;
            }
            default: {
                float r = b.radius;
                if (b.type.habitable) {
                    sr.setColor(0.5f, 0.8f, 1f, 0.18f);
                    sr.circle(b.x, b.y, r * 1.12f, segs(r));
                }
                if (b.type == PlanetType.GAS) {
                    sr.setColor(c.r, c.g, c.b, 0.25f);
                    ring(sr, b.x, b.y, r * 1.45f, 4 * Math.max(1, zoom * 0.5f), 48);
                }
                sr.setColor(c.r * 0.45f, c.g * 0.45f, c.b * 0.5f, 1f);
                sr.circle(b.x, b.y, r, segs(r));
                sr.setColor(c);
                sr.circle(b.x - r * 0.12f, b.y + r * 0.12f, r * 0.84f, segs(r));
                if (b.type == PlanetType.HABITABLE || b.type == PlanetType.DEFENDED) {
                    sr.setColor(0.25f, 0.6f, 0.3f, 1f);
                    sr.circle(b.x - r * 0.3f, b.y + r * 0.05f, r * 0.3f, 12);
                    sr.circle(b.x + r * 0.25f, b.y + r * 0.35f, r * 0.2f, 12);
                } else if (b.type == PlanetType.VOLCANIC) {
                    sr.setColor(1f, 0.55f, 0.15f, 0.8f);
                    sr.circle(b.x + r * 0.2f, b.y - r * 0.2f, r * 0.15f, 8);
                    sr.circle(b.x - r * 0.35f, b.y + r * 0.3f, r * 0.1f, 8);
                } else if (b.type == PlanetType.CRYSTAL) {
                    sr.setColor(0.8f, 0.75f, 1f, 0.8f);
                    sr.triangle(b.x - r * 0.3f, b.y, b.x - r * 0.15f, b.y + r * 0.4f, b.x, b.y);
                    sr.triangle(b.x + r * 0.1f, b.y - r * 0.3f, b.x + r * 0.25f, b.y + r * 0.1f, b.x + r * 0.4f, b.y - r * 0.3f);
                } else if (b.type == PlanetType.RUINS) {
                    sr.setColor(1f, 0.85f, 0.35f, 0.85f);
                    for (int i = 0; i < 5; i++) {
                        float a = i * 1.26f;
                        sr.rect(b.x + (float) Math.cos(a) * r * 0.45f - 4, b.y + (float) Math.sin(a) * r * 0.45f - 4, 8, 8);
                    }
                }
                sr.setColor(1f, 1f, 1f, 0.15f);
                sr.circle(b.x - r * 0.35f, b.y + r * 0.35f, r * 0.28f, 12);
                if (b.guarded()) {
                    sr.setColor(DEFENDED_C.r, DEFENDED_C.g, DEFENDED_C.b, 0.5f);
                    dashedRing(sr, b.x, b.y, r + 75, 2.5f * Math.max(1, zoom * 0.6f), 36, w.time * 0.1f);
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------------ units

    private float ux, uy, uc, us;

    private void tri(ShapeRenderer sr, float ax, float ay, float bx, float by, float cx, float cy) {
        sr.triangle(ux + ax * uc - ay * us, uy + ax * us + ay * uc,
                ux + bx * uc - by * us, uy + bx * us + by * uc,
                ux + cx * uc - cy * us, uy + cx * us + cy * uc);
    }

    private void quad(ShapeRenderer sr, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        tri(sr, ax, ay, bx, by, cx, cy);
        tri(sr, ax, ay, cx, cy, dx, dy);
    }

    private final float[] buoyPos = new float[2];

    private void drawUnit(ShapeRenderer sr, World w, Unit u) {
        Color oc = u.owner.color;
        float alpha = u.cloaked(w.time) ? 0.35f : 1f; // cloaked ships (Phantom, Ghost Drive) are drawn faint
        float r = u.radius;
        ux = u.x;
        uy = u.y;
        float ang = u.angle;
        if (u.cls == ShipClass.TURRET && u.target != null && !u.target.dead)
            ang = (float) Math.atan2(u.target.y - u.y, u.target.x - u.x);
        if (u.cls == ShipClass.HUB) ang = w.time * 0.2f;
        uc = (float) Math.cos(ang);
        us = (float) Math.sin(ang);
        float dark = 0.55f;

        // shield flash
        if (u.shield > 0 && w.time - u.lastHit < 0.25f) {
            sr.setColor(SHIELD_C.r, SHIELD_C.g, SHIELD_C.b, 0.25f);
            sr.circle(u.x, u.y, r * 1.35f, segs(r * 1.35f));
        }
        if (u.tauntTimer > 0) {
            sr.setColor(1f, 0.85f, 0.3f, 0.15f);
            sr.circle(u.x, u.y, r * 1.6f, segs(r * 1.6f));
        }

        switch (u.cls) {
            case MOTHERSHIP:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r, 0, r * 0.35f, r * 0.6f, -r * 0.8f, r * 0.65f, -r, 0);
                quad(sr, r, 0, -r, 0, -r * 0.8f, -r * 0.65f, r * 0.35f, -r * 0.6f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.75f, 0, r * 0.2f, r * 0.35f, -r * 0.6f, r * 0.35f, -r * 0.6f, 0);
                quad(sr, r * 0.75f, 0, -r * 0.6f, 0, -r * 0.6f, -r * 0.35f, r * 0.2f, -r * 0.35f);
                sr.setColor(1f, 0.9f, 0.6f, 0.8f * alpha);
                quad(sr, -r * 0.82f, r * 0.45f, -r * 0.95f, r * 0.45f, -r * 0.95f, -r * 0.45f, -r * 0.82f, -r * 0.45f);
                sr.setColor(1f, 1f, 1f, 0.8f);
                sr.circle(u.x + uc * r * 0.3f, u.y + us * r * 0.3f, r * 0.12f, 12);
                break;
            case HUB:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r, 0, 0, r, -r, 0, 0, -r);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.6f, 0, 0, r * 0.6f, -r * 0.6f, 0, 0, -r * 0.6f);
                sr.setColor(0.9f, 0.8f, 1f, 0.9f);
                sr.circle(u.x, u.y, r * 0.3f, 16);
                break;
            case FACTORY: {
                // hexagonal hull with a glowing assembly bay and rotating gantry arms
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                for (int i = 0; i < 6; i++) {
                    float a0 = i * 1.0472f, a1 = a0 + 1.0472f;
                    sr.triangle(u.x, u.y, u.x + (float) Math.cos(a0) * r, u.y + (float) Math.sin(a0) * r,
                            u.x + (float) Math.cos(a1) * r, u.y + (float) Math.sin(a1) * r);
                }
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                float spin = w.time * 0.4f;
                for (int i = 0; i < 3; i++) {
                    float a = spin + i * 2.094f;
                    sr.rectLine(u.x, u.y, u.x + (float) Math.cos(a) * r * 1.1f, u.y + (float) Math.sin(a) * r * 1.1f, r * 0.14f);
                }
                boolean busy = u.queue != null && !u.queue.isEmpty();
                float glow = busy ? 0.6f + 0.4f * (float) Math.sin(w.time * 6) : 0.35f;
                sr.setColor(1f, 0.8f, 0.4f, glow);
                sr.rect(u.x - r * 0.35f, u.y - r * 0.35f, r * 0.7f, r * 0.7f);
                break;
            }
            case SOLAR: {
                // collector dish facing the star (u.angle) with two panel wings
                sr.setColor(0.15f, 0.25f, 0.55f, alpha);
                quad(sr, r * 0.3f, r * 1.5f, -r * 0.3f, r * 1.5f, -r * 0.3f, r * 0.35f, r * 0.3f, r * 0.35f);
                quad(sr, r * 0.3f, -r * 0.35f, -r * 0.3f, -r * 0.35f, -r * 0.3f, -r * 1.5f, r * 0.3f, -r * 1.5f);
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r * 0.55f, 14);
                float glow = 0.6f + 0.3f * (float) Math.sin(w.time * 3 + u.id);
                sr.setColor(1f, 0.85f, 0.35f, glow * alpha);
                tri(sr, r * 0.9f, 0, r * 0.2f, r * 0.45f, r * 0.2f, -r * 0.45f);
                break;
            }
            case MINER: {
                // squat body with a drill arm toward the rock it is mining
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 0.6f, r * 0.8f, -r * 0.8f, r * 0.8f, -r * 0.8f, -r * 0.8f, r * 0.6f, -r * 0.8f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.4f, r * 0.45f, -r * 0.5f, r * 0.45f, -r * 0.5f, -r * 0.45f, r * 0.4f, -r * 0.45f);
                Body rock = u.body != null && !u.body.dead ? u.body : null;
                if (rock != null) {
                    float a = (float) Math.atan2(rock.y - u.y, rock.x - u.x);
                    float ex = rock.x - (float) Math.cos(a) * rock.radius * 0.8f, ey = rock.y - (float) Math.sin(a) * rock.radius * 0.8f;
                    float flick = 0.35f + 0.3f * (float) Math.sin(w.time * 18 + u.id);
                    sr.setColor(0.8f, 0.8f, 0.85f, flick * alpha);
                    sr.rectLine(u.x, u.y, ex, ey, 2.5f * Math.max(1, zoom * 0.7f));
                } else {
                    sr.setColor(0.6f, 0.6f, 0.6f, alpha); // idle: nothing left in reach
                    sr.circle(u.x, u.y, r * 0.2f, 8);
                }
                break;
            }
            case SHIPYARD:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.rect(u.x - r, u.y - r, r * 2, r * 0.4f);
                sr.rect(u.x - r, u.y + r * 0.6f, r * 2, r * 0.4f);
                sr.rect(u.x - r, u.y - r, r * 0.4f, r * 2);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.rect(u.x - r * 0.3f, u.y - r * 0.3f, r * 0.6f, r * 0.6f);
                break;
            case OUTPOST:
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r, 0, 0, r, -r, 0, 0, -r);
                sr.setColor(1f, 1f, 1f, 0.8f);
                sr.rectLine(u.x, u.y, u.x, u.y + r * 1.6f, 2);
                if (u.home != null && u.home.type.rare != null) {
                    Color rc = u.home.type.rare.color;
                    sr.setColor(rc.r, rc.g, rc.b, 0.6f + 0.4f * (float) Math.sin(w.time * 3));
                    sr.circle(u.x, u.y + r * 1.6f, 4, 8);
                }
                break;
            case COLONY:
                if (u.home != null && u.home.shieldPoints > 0 && u.shield > 1) {
                    // planetary shield dome: brighter with more points, fades as it drains
                    float sp = u.shield / u.maxShield();
                    float dome = r + 26 + u.home.shieldPoints * 6;
                    sr.setColor(SHIELD_C.r, SHIELD_C.g, SHIELD_C.b, (0.04f + 0.02f * u.home.shieldPoints) * sp);
                    sr.circle(u.x, u.y, dome, segs(dome));
                    sr.setColor(SHIELD_C.r, SHIELD_C.g, SHIELD_C.b, 0.5f * sp);
                    ring(sr, u.x, u.y, dome, 1.5f * Math.max(1, zoom * 0.6f), segs(dome));
                }
                sr.setColor(oc.r, oc.g, oc.b, 0.85f);
                ring(sr, u.x, u.y, r, 3f * Math.max(1, zoom * 0.6f), segs(r));
                for (int i = 0; i < 6; i++) {
                    float a = i * 1.047f + w.time * 0.05f;
                    sr.circle(u.x + (float) Math.cos(a) * r, u.y + (float) Math.sin(a) * r, 4 * Math.max(1, zoom * 0.5f), 8);
                }
                break;
            case TURRET:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r, 12);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.5f, 0, 0, r * 0.5f, -r * 0.5f, 0, 0, -r * 0.5f);
                sr.rectLine(u.x, u.y, u.x + uc * r * 1.4f, u.y + us * r * 1.4f, 3);
                break;
            case HARVESTER:
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r, 0, -r * 0.8f, r * 0.75f, -r * 0.8f, -r * 0.75f);
                if (u.cargo > 0) {
                    if (u.cargoEnergy) sr.setColor(1f, 0.85f, 0.3f, 1f);
                    else sr.setColor(0.7f, 0.7f, 0.75f, 1f);
                    tri(sr, -r * 0.1f, 0, -r * 0.7f, r * 0.4f, -r * 0.7f, -r * 0.4f);
                }
                break;
            case PIRATE_BASE: { // jagged ring station with spikes and blinking red running lights
                float spin = w.time * 0.15f;
                sr.setColor(1f, 0.25f, 0.2f, 0.05f + 0.03f * (float) Math.sin(w.time * 2));
                sr.circle(u.x, u.y, r * 1.9f, segs(r * 1.9f));
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                for (int i = 0; i < 9; i++) { // spikes
                    double a = spin + i * Math.PI * 2 / 9;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    float a2 = (float) (a + 0.22), a3 = (float) (a - 0.22);
                    sr.triangle(u.x + ca * r * 1.45f, u.y + sa * r * 1.45f,
                            u.x + (float) Math.cos(a2) * r * 0.85f, u.y + (float) Math.sin(a2) * r * 0.85f,
                            u.x + (float) Math.cos(a3) * r * 0.85f, u.y + (float) Math.sin(a3) * r * 0.85f);
                }
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.circle(u.x, u.y, r, segs(r));
                sr.setColor(0.08f, 0.07f, 0.07f, 1f);
                sr.circle(u.x, u.y, r * 0.62f, segs(r));
                sr.setColor(oc.r * 0.8f, oc.g * 0.8f, oc.b * 0.8f, alpha);
                sr.circle(u.x, u.y, r * 0.3f, 14);
                for (int i = 0; i < 6; i++) { // running lights
                    double a = -spin * 2 + i * Math.PI / 3;
                    boolean on = ((int) (w.time * 3) + i) % 3 == 0;
                    sr.setColor(1f, 0.2f, 0.15f, on ? 1f : 0.3f);
                    sr.circle(u.x + (float) Math.cos(a) * r * 0.8f, u.y + (float) Math.sin(a) * r * 0.8f, Math.max(2f, r * 0.07f), 6);
                }
                break;
            }
            case BARGE: { // wide, boxy hauler with an ore hold that fills up
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 0.9f, r * 0.7f, -r, r * 0.8f, -r, -r * 0.8f, r * 0.9f, -r * 0.7f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 1.1f, r * 0.35f, r * 0.5f, r * 0.55f, r * 0.5f, -r * 0.55f, r * 1.1f, -r * 0.35f);
                sr.setColor(0.12f, 0.12f, 0.15f, 1f);
                quad(sr, r * 0.35f, r * 0.55f, -r * 0.8f, r * 0.6f, -r * 0.8f, -r * 0.6f, r * 0.35f, -r * 0.55f);
                float fill = Math.min(1f, u.cargo / Math.max(1f, w.economy.capacity(u)));
                if (fill > 0) {
                    if (u.cargoEnergy) sr.setColor(1f, 0.85f, 0.3f, 1f);
                    else sr.setColor(0.7f, 0.7f, 0.75f, 1f);
                    float back = -r * 0.8f, len = r * 1.15f * fill;
                    quad(sr, back + len, r * 0.45f, back, r * 0.45f, back, -r * 0.45f, back + len, -r * 0.45f);
                }
                break;
            }
            case COLONY_SHIP:
            case SURVEYOR:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r * 0.8f, 14);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r, 0, -r * 0.5f, r * 0.6f, -r * 0.5f, -r * 0.6f);
                sr.setColor(u.cls == ShipClass.COLONY_SHIP ? new Color(0.5f, 1f, 0.6f, 1f) : new Color(1f, 0.85f, 0.4f, 1f));
                sr.circle(u.x, u.y, r * 0.25f, 8);
                break;
            case BULWARK:
            case AEGIS:
            case GUNSHIP:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 0.8f, r * 0.55f, -r, r, -r, -r, r * 0.8f, -r * 0.55f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r, r * 0.3f, -r * 0.6f, r * 0.6f, -r * 0.6f, -r * 0.6f, r, -r * 0.3f);
                if (u.cls == ShipClass.AEGIS) {
                    sr.setColor(SHIELD_C.r, SHIELD_C.g, SHIELD_C.b, 0.08f + 0.04f * (float) Math.sin(w.time * 3));
                    sr.circle(u.x, u.y, u.s.aura, segs(u.s.aura));
                }
                break;
            case LONGBOW:
            case PHANTOM:
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r * 1.3f, 0, -r, r * 0.45f, -r, -r * 0.45f);
                sr.setColor(1f, 1f, 0.85f, 0.9f * alpha);
                sr.rectLine(u.x, u.y, u.x + uc * r * 1.8f, u.y + us * r * 1.8f, 1.5f);
                break;
            case MENDER: { // Repair Station: round hub, four docking arms, rotating ring of repair lights
                sr.setColor(0.4f, 1f, 0.5f, 0.04f + 0.02f * (float) Math.sin(w.time * 2 + u.id));
                sr.circle(u.x, u.y, u.s.healRange, segs(u.s.healRange));
                sr.setColor(0.4f, 1f, 0.5f, 0.22f);
                ring(sr, u.x, u.y, u.s.healRange, 1.2f * zoom, 48);
                for (int i = 0; i < 4; i++) { // docking arms with a docked ship silhouette on two of them
                    double a = Math.PI / 4 + i * Math.PI / 2;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                    sr.rectLine(u.x + ca * r * 0.6f, u.y + sa * r * 0.6f, u.x + ca * r * 1.45f, u.y + sa * r * 1.45f, r * 0.28f);
                    sr.setColor(oc.r, oc.g, oc.b, alpha);
                    sr.circle(u.x + ca * r * 1.5f, u.y + sa * r * 1.5f, r * 0.2f, 8);
                    if (i % 2 == 0) {
                        float px = u.x + ca * r * 1.5f - sa * r * 0.35f, py = u.y + sa * r * 1.5f + ca * r * 0.35f;
                        sr.setColor(0.6f, 0.65f, 0.7f, 0.8f * alpha);
                        sr.triangle(px + ca * 7, py + sa * 7, px - ca * 5 - sa * 4, py - sa * 5 + ca * 4, px - ca * 5 + sa * 4, py - sa * 5 - ca * 4);
                    }
                }
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r * 0.85f, segs(r));
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.circle(u.x, u.y, r * 0.62f, segs(r));
                float spin = w.time * 0.7f;
                for (int i = 0; i < 10; i++) { // rotating ring of green repair lights
                    double a = spin + i * Math.PI / 5;
                    boolean on = ((int) (w.time * 4) + i) % 2 == 0;
                    sr.setColor(0.4f, 1f, 0.5f, on ? 1f : 0.35f);
                    sr.circle(u.x + (float) Math.cos(a) * r * 1.05f, u.y + (float) Math.sin(a) * r * 1.05f, Math.max(1.5f, r * 0.07f), 6);
                }
                float pulse = 0.75f + 0.25f * (float) Math.sin(w.time * 3);
                sr.setColor(0.4f, 1f, 0.5f, pulse);
                sr.rect(u.x - r * 0.4f, u.y - r * 0.11f, r * 0.8f, r * 0.22f);
                sr.rect(u.x - r * 0.11f, u.y - r * 0.4f, r * 0.22f, r * 0.8f);
                break;
            }
            case BEACON: { // Claim Beacon: a tall mast with a pulsing ring broadcasting the claim
                float p = (w.time * 0.5f + u.id * 0.13f) % 1f;
                sr.setColor(oc.r, oc.g, oc.b, 0.25f * (1f - p));
                ring(sr, u.x, u.y, r * (1.5f + p * 6f), 2f * Math.max(1, zoom * 0.6f), 32);
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.triangle(u.x - r * 0.7f, u.y - r, u.x + r * 0.7f, u.y - r, u.x, u.y + r * 1.4f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.circle(u.x, u.y + r * 0.9f, r * 0.45f, 10);
                sr.setColor(1f, 1f, 1f, 0.5f + 0.5f * (float) Math.sin(w.time * 5));
                sr.circle(u.x, u.y + r * 0.9f, r * 0.18f, 6);
                break;
            }
            case PLATFORM:
            case ION_CANNON: { // gun platforms: octagonal base with a turret barrel toward the target
                boolean ion = u.cls == ShipClass.ION_CANNON;
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r, 8);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.circle(u.x, u.y, r * 0.6f, 8);
                float ta = u.target != null && !u.target.dead ? (float) Math.atan2(u.target.y - u.y, u.target.x - u.x) : w.time * 0.2f + u.id;
                sr.setColor(0.75f, 0.78f, 0.85f, alpha);
                sr.rectLine(u.x, u.y, u.x + (float) Math.cos(ta) * r * (ion ? 2.1f : 1.5f), u.y + (float) Math.sin(ta) * r * (ion ? 2.1f : 1.5f), r * (ion ? 0.45f : 0.3f));
                if (ion) {
                    float charge = 1f - Math.max(0, u.cooldown) / u.s.cooldown;
                    sr.setColor(0.5f, 0.85f, 1f, 0.3f + 0.7f * charge);
                    sr.circle(u.x, u.y, r * 0.35f * (0.5f + 0.5f * charge), 10);
                }
                break;
            }
            case REFINERY: { // ore refinery: boxy plant with smelting stacks
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.rect(u.x - r, u.y - r * 0.7f, r * 2, r * 1.4f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.rect(u.x - r * 0.8f, u.y - r * 0.5f, r * 1.6f, r);
                for (int i = 0; i < 3; i++) {
                    float sx = u.x - r * 0.6f + i * r * 0.6f;
                    sr.setColor(0.25f, 0.25f, 0.3f, alpha);
                    sr.rect(sx - r * 0.12f, u.y + r * 0.5f, r * 0.24f, r * 0.7f);
                    sr.setColor(1f, 0.55f, 0.2f, 0.5f + 0.4f * (float) Math.sin(w.time * 4 + i));
                    sr.circle(sx, u.y + r * 1.25f, r * 0.13f, 6);
                }
                break;
            }
            case SENSOR: { // sensor array: dish on a pylon with a sweeping scan line
                float sweep = w.time * 1.4f + u.id;
                sr.setColor(oc.r, oc.g, oc.b, 0.08f);
                sr.circle(u.x, u.y, r * 6f, 24);
                sr.setColor(oc.r, oc.g, oc.b, 0.5f);
                sr.rectLine(u.x, u.y, u.x + (float) Math.cos(sweep) * r * 6f, u.y + (float) Math.sin(sweep) * r * 6f, 1.5f * Math.max(1, zoom * 0.5f));
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r * 0.7f, 10);
                sr.setColor(0.8f, 0.85f, 0.9f, alpha);
                sr.triangle(u.x + (float) Math.cos(sweep) * r, u.y + (float) Math.sin(sweep) * r,
                        u.x + (float) Math.cos(sweep + 2.3) * r * 0.8f, u.y + (float) Math.sin(sweep + 2.3) * r * 0.8f,
                        u.x + (float) Math.cos(sweep - 2.3) * r * 0.8f, u.y + (float) Math.sin(sweep - 2.3) * r * 0.8f);
                break;
            }
            case ASCENSION: { // the wonder: concentric rotating rings around a blazing core
                for (int k = 0; k < 3; k++) {
                    float rr = r * (1f - k * 0.25f), rot = w.time * (0.3f + k * 0.25f) * (k % 2 == 0 ? 1 : -1);
                    sr.setColor(0.6f + 0.2f * k, 0.85f, 1f, 0.8f);
                    for (int i = 0; i < 12; i++) {
                        double a0 = rot + i * Math.PI / 6, a1 = a0 + Math.PI / 9;
                        sr.rectLine(u.x + (float) Math.cos(a0) * rr, u.y + (float) Math.sin(a0) * rr,
                                u.x + (float) Math.cos(a1) * rr, u.y + (float) Math.sin(a1) * rr, 4f);
                    }
                }
                sr.setColor(1f, 0.95f, 0.75f, 0.25f + 0.15f * (float) Math.sin(w.time * 2));
                sr.circle(u.x, u.y, r * 0.5f, 24);
                sr.setColor(1f, 1f, 0.9f, 1f);
                sr.circle(u.x, u.y, r * 0.22f, 16);
                break;
            }
            case CORSAIR: // slim dart with swept wings
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r * 1.4f, 0, -r * 0.7f, r * 0.9f, -r * 0.3f, 0);
                tri(sr, r * 1.4f, 0, -r * 0.3f, 0, -r * 0.7f, -r * 0.9f);
                sr.setColor(1f, 0.85f, 0.5f, alpha);
                tri(sr, r * 0.7f, 0, r * 0.1f, r * 0.2f, r * 0.1f, -r * 0.2f);
                break;
            case PYRE: { // stubby armoured hull with a glowing flamer nozzle up front
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 0.8f, r * 0.55f, -r, r * 0.8f, -r, -r * 0.8f, r * 0.8f, -r * 0.55f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.4f, r * 0.4f, -r * 0.7f, r * 0.55f, -r * 0.7f, -r * 0.55f, r * 0.4f, -r * 0.4f);
                float flick = 0.8f + 0.2f * (float) Math.sin(w.time * 14 + u.id);
                sr.setColor(1f, 0.55f, 0.15f, alpha);
                tri(sr, r * 1.35f * flick, 0, r * 0.75f, r * 0.3f, r * 0.75f, -r * 0.3f);
                sr.setColor(1f, 0.9f, 0.5f, alpha);
                sr.circle(ux + uc * r * 0.85f, uy + us * r * 0.85f, r * 0.16f, 8);
                break;
            }
            case TEMPEST: // cruiser hull with two missile pods
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 1.1f, r * 0.3f, -r, r * 0.45f, -r, -r * 0.45f, r * 1.1f, -r * 0.3f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.4f, r * 0.85f, -r * 0.6f, r * 0.85f, -r * 0.6f, r * 0.5f, r * 0.4f, r * 0.5f);
                quad(sr, r * 0.4f, -r * 0.5f, -r * 0.6f, -r * 0.5f, -r * 0.6f, -r * 0.85f, r * 0.4f, -r * 0.85f);
                sr.setColor(1f, 0.5f, 0.3f, alpha);
                for (int i = -1; i <= 1; i += 2)
                    sr.circle(ux + uc * r * 0.3f - us * i * r * 0.68f, uy + us * r * 0.3f + uc * i * r * 0.68f, r * 0.1f, 6);
                break;
            case SOVEREIGN: // battleship: long armoured hull, twin turret blocks, bright bridge
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 1.3f, 0, r * 0.4f, r * 0.6f, -r, r * 0.55f, -r, -r * 0.55f);
                quad(sr, r * 1.3f, 0, -r, -r * 0.55f, r * 0.4f, -r * 0.6f, r * 1.3f, 0);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 0.9f, r * 0.2f, -r * 0.8f, r * 0.3f, -r * 0.8f, -r * 0.3f, r * 0.9f, -r * 0.2f);
                sr.setColor(0.2f, 0.2f, 0.25f, 1f);
                for (int i = 0; i < 2; i++) {
                    float off = r * (0.45f - i * 0.8f);
                    sr.circle(ux + uc * off, uy + us * off, r * 0.17f, 10);
                }
                sr.setColor(u.overchargeTimer > 0 ? new Color(1f, 0.6f, 0.2f, 1f) : new Color(0.7f, 0.9f, 1f, 0.9f));
                sr.circle(ux - uc * r * 0.55f, uy - us * r * 0.55f, r * 0.12f, 8);
                break;
            case COMMAND: { // command station: octagonal deck, a bridge tower and a sweeping radar boom
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                for (int i = 0; i < 8; i++) {
                    float a0 = i * 0.7854f + 0.3927f, a1 = (i + 1) * 0.7854f + 0.3927f;
                    tri(sr, 0, 0, (float) Math.cos(a0) * r, (float) Math.sin(a0) * r, (float) Math.cos(a1) * r, (float) Math.sin(a1) * r);
                }
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                sr.circle(u.x, u.y, r * 0.55f, segs(r * 0.55f));
                sr.setColor(0.15f, 0.15f, 0.2f, 1f);
                sr.circle(u.x, u.y, r * 0.3f, 12);
                float ra = w.time * 1.4f;
                sr.setColor(1f, 0.85f, 0.45f, 0.9f * alpha);
                sr.rectLine(u.x, u.y, u.x + (float) Math.cos(ra) * r * 0.95f, u.y + (float) Math.sin(ra) * r * 0.95f, 2.5f);
                sr.circle(u.x + (float) Math.cos(ra) * r * 0.95f, u.y + (float) Math.sin(ra) * r * 0.95f, r * 0.12f, 8);
                break;
            }
            case DEFENDER: { // guardian: a broad hexagonal armour slab with a forward shield plate and twin turrets
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                for (int i = 0; i < 6; i++) {
                    float a0 = i * 1.0472f, a1 = (i + 1) * 1.0472f;
                    tri(sr, 0, 0, (float) Math.cos(a0) * r, (float) Math.sin(a0) * r, (float) Math.cos(a1) * r, (float) Math.sin(a1) * r);
                }
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 1.1f, r * 0.75f, r * 0.75f, r * 0.75f, r * 0.75f, -r * 0.75f, r * 1.1f, -r * 0.75f);
                quad(sr, r * 0.5f, r * 0.3f, -r * 0.6f, r * 0.45f, -r * 0.6f, -r * 0.45f, r * 0.5f, -r * 0.3f);
                sr.setColor(0.18f, 0.18f, 0.24f, 1f);
                for (int i = -1; i <= 1; i += 2)
                    sr.circle(ux - us * i * r * 0.55f, uy + uc * i * r * 0.55f, r * 0.2f, 10);
                sr.setColor(SHIELD_C.r, SHIELD_C.g, SHIELD_C.b, 0.35f * alpha);
                quad(sr, r * 1.25f, r * 0.85f, r * 1.12f, r * 0.85f, r * 1.12f, -r * 0.85f, r * 1.25f, -r * 0.85f);
                break;
            }
            case HALCYON: // repair cruiser: rounded hull with a green cross and a soft heal field
                sr.setColor(0.4f, 1f, 0.5f, 0.05f);
                sr.circle(u.x, u.y, u.s.healRange, segs(u.s.healRange));
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r, segs(r));
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r * 1.25f, 0, -r * 0.4f, r * 0.75f, -r * 0.4f, -r * 0.75f);
                sr.setColor(0.4f, 1f, 0.5f, 1f);
                sr.rect(u.x - r * 0.35f, u.y - r * 0.1f, r * 0.7f, r * 0.2f);
                sr.rect(u.x - r * 0.1f, u.y - r * 0.35f, r * 0.2f, r * 0.7f);
                break;
            case HIVE:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                sr.circle(u.x, u.y, r, segs(r));
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r * 1.1f, 0, -r * 0.5f, r * 0.7f, -r * 0.5f, -r * 0.7f);
                sr.setColor(0.1f, 0.1f, 0.15f, 1f);
                for (int i = 0; i < 3; i++) {
                    float a = ang + 2.1f + i * 1.05f;
                    sr.circle(u.x + (float) Math.cos(a) * r * 0.65f, u.y + (float) Math.sin(a) * r * 0.65f, r * 0.18f, 8);
                }
                break;
            case LEVIATHAN:
            case DREADNOUGHT:
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                quad(sr, r * 1.2f, 0, r * 0.3f, r * 0.55f, -r, r * 0.7f, -r, 0);
                quad(sr, r * 1.2f, 0, -r, 0, -r, -r * 0.7f, r * 0.3f, -r * 0.55f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r, 0, r * 0.1f, r * 0.3f, -r * 0.8f, r * 0.35f, -r * 0.8f, -r * 0.35f);
                tri(sr, r, 0, -r * 0.8f, -r * 0.35f, r * 0.1f, -r * 0.3f);
                for (int i = -1; i <= 1; i += 2) {
                    sr.setColor(0.15f, 0.15f, 0.2f, 1f);
                    float px = ux + (-r * 0.2f) * uc - (i * r * 0.62f) * us, py = uy + (-r * 0.2f) * us + (i * r * 0.62f) * uc;
                    sr.circle(px, py, r * 0.14f, 10);
                }
                sr.setColor(1f, 0.7f, 0.4f, 0.8f);
                quad(sr, -r * 0.95f, r * 0.5f, -r * 1.1f, r * 0.5f, -r * 1.1f, -r * 0.5f, -r * 0.95f, -r * 0.5f);
                break;
            case WISP:
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                quad(sr, r * 1.3f, 0, 0, r * 0.6f, -r * 0.9f, 0, 0, -r * 0.6f);
                break;
            case PATROL: // sleek arrowhead with twin engine glows
                sr.setColor(oc.r * dark, oc.g * dark, oc.b * dark, alpha);
                tri(sr, r * 1.5f, 0, -r * 0.9f, r * 0.85f, -r * 0.9f, -r * 0.85f);
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r * 1.3f, 0, -r * 0.5f, r * 0.45f, -r * 0.5f, -r * 0.45f);
                sr.setColor(0.5f, 0.85f, 1f, 0.9f * alpha);
                for (int i = -1; i <= 1; i += 2)
                    sr.circle(ux - uc * r * 0.95f - us * i * r * 0.5f, uy - us * r * 0.95f + uc * i * r * 0.5f, Math.max(1.5f, r * 0.18f), 6);
                break;
            case PATROL_SKIFF: // tiny dart
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r * 1.4f, 0, -r * 0.8f, r * 0.6f, -r * 0.8f, -r * 0.6f);
                break;
            default: // lancer, drone, raider, guard
                sr.setColor(oc.r, oc.g, oc.b, alpha);
                tri(sr, r, 0, -r * 0.8f, r * 0.75f, -r * 0.35f, 0);
                tri(sr, r, 0, -r * 0.35f, 0, -r * 0.8f, -r * 0.75f);
                break;
        }
        // engine glow for moving ships
        if (!u.s.stationary && (u.vx * u.vx + u.vy * u.vy) > 100 && r < 40) {
            sr.setColor(1f, 0.75f, 0.4f, 0.6f * alpha);
            sr.circle(u.x - uc * r * 0.9f, u.y - us * r * 0.9f, Math.max(1.5f, r * 0.18f), 6);
        }
        // warp cruise: a long bright streak behind the ship
        if (u.warping && (u.vx * u.vx + u.vy * u.vy) > 100) {
            float sp = (float) Math.hypot(u.vx, u.vy);
            float len = Math.min(900f, sp * 0.6f);
            float tx = u.x - u.vx / sp * len, ty = u.y - u.vy / sp * len;
            sr.setColor(0.75f, 0.9f, 1f, 0.12f * alpha);
            sr.rectLine(u.x, u.y, tx, ty, Math.max(3f, r * 1.2f));
            sr.setColor(0.9f, 0.97f, 1f, 0.55f * alpha);
            sr.rectLine(u.x, u.y, u.x - u.vx / sp * len * 0.5f, u.y - u.vy / sp * len * 0.5f, Math.max(1.5f, r * 0.35f));
        } else if (u.warpCharge > 0) { // spooling up: a tightening ring
            sr.setColor(0.75f, 0.9f, 1f, 0.2f + 0.3f * u.warpCharge / spacerts.systems.MovementSystem.WARP_SPOOL);
            ring(sr, u.x, u.y, r * (3f - 1.6f * u.warpCharge / spacerts.systems.MovementSystem.WARP_SPOOL), 1.5f * Math.max(1, zoom * 0.5f), 16);
        }
        // colony speed boost: a blue thrust trail
        if (u.boosted && (u.vx * u.vx + u.vy * u.vy) > 100) {
            float sp = (float) Math.hypot(u.vx, u.vy);
            float tx = u.x - u.vx / sp * (r + 22), ty = u.y - u.vy / sp * (r + 22);
            sr.setColor(0.45f, 0.85f, 1f, 0.35f * alpha);
            sr.rectLine(u.x - u.vx / sp * r * 0.8f, u.y - u.vy / sp * r * 0.8f, tx, ty, Math.max(1.5f, r * 0.3f));
        }
        // stunned by a Martyr Shockwave: a crackling blue halo
        if (u.stunTimer > 0) {
            float flick = 0.35f + 0.3f * (float) Math.sin(w.time * 25 + u.id);
            sr.setColor(0.55f, 0.8f, 1f, flick);
            ring(sr, u.x, u.y, r * 1.5f, 2f * Math.max(1, zoom * 0.5f), 16);
        }
        // a relocating Asteroid Miner kicks up a dust trail
        if (u.relocating) {
            for (int i = 1; i <= 3; i++) {
                sr.setColor(0.6f, 0.55f, 0.45f, 0.25f / i);
                sr.circle(u.x - uc * r * (1.2f + i * 0.6f), u.y - us * r * (1.2f + i * 0.6f), r * (0.3f + 0.15f * i), 8);
            }
        }
        // retreating for repairs: an amber double chevron above the ship
        if (u.retreating) {
            float s = Math.max(5f, 5f * zoom), cy = u.y + r + 16 * Math.max(1f, zoom * 0.6f) + s;
            sr.setColor(1f, 0.75f, 0.3f, 0.6f + 0.3f * (float) Math.sin(w.time * 6));
            for (int i = 0; i < 2; i++) {
                float yy = cy - i * s * 0.9f;
                sr.rectLine(u.x - s, yy, u.x, yy - s * 0.7f, 1.5f * Math.max(1f, zoom * 0.5f));
                sr.rectLine(u.x, yy - s * 0.7f, u.x + s, yy, 1.5f * Math.max(1f, zoom * 0.5f));
            }
        }
    }

    private void healthBar(ShapeRenderer sr, Unit u) {
        float bw = Math.max(22, u.radius * 1.8f) * Math.max(1f, zoom * 0.5f);
        float bh = 3.5f * Math.max(1f, zoom * 0.6f);
        float x = u.x - bw / 2, y = u.y + u.radius + 6 * Math.max(1, zoom * 0.6f);
        float pct = Math.max(0, u.hp / u.maxHp());
        sr.setColor(HP_BG);
        sr.rect(x, y, bw, bh);
        sr.setColor(1f - pct, 0.25f + pct * 0.7f, 0.25f, 1f);
        sr.rect(x, y, bw * pct, bh);
        if (u.maxShield() > 0) {
            float sp = u.shield / u.maxShield();
            sr.setColor(SHIELD_C);
            sr.rect(x, y + bh + 1, bw * sp, bh * 0.6f);
        }
    }

    // ------------------------------------------------------------------ text labels

    private void labels(World w) {
        SpriteBatch batch = gs.game.batch;
        BitmapFont font = gs.game.font;
        OrthographicCamera cam = gs.cam;
        batch.setProjectionMatrix(gs.hudCam.combined);
        batch.begin();
        if (zoom < 3.2f) {
            font.getData().setScale(0.8f);
            for (Body b : w.bodies) {
                if (b.dead || (b.depletes() && !b.giant) || !inView(b.x, b.y, b.radius) || !w.player.vision.isExplored(b.x, b.y)) continue;
                cam.project(v3.set(b.x, b.y - b.radius - 14, 0));
                String s = b.kind == Body.Kind.STAR || b.isGate() || b.giant ? b.name + (b.giant ? "  (" + String.format("%,d", (long) b.metal) + " metal)" : "") : b.name + "  (" + b.type.title + ")";
                if (b.kind == Body.Kind.PLANET && b.guarded()) {
                    font.setColor(1f, 0.45f, 0.4f, 0.9f);
                    font.draw(batch, "DEFENDED - " + b.turretsAlive() + " turrets", v3.x - 150, v3.y - 14, 300, Align.center, false);
                }
                font.setColor(b.colonized() ? b.colony.owner.color : new Color(0.75f, 0.8f, 0.9f, 0.75f));
                font.draw(batch, s, v3.x - 150, v3.y, 300, Align.center, false);
            }
        }
        font.getData().setScale(0.9f);
        for (Effect e : w.effects) {
            if (e.kind != Effect.Kind.TEXT || !inView(e.x, e.y, 50)) continue;
            cam.project(v3.set(e.x, e.y, 0));
            font.setColor(e.color.r, e.color.g, e.color.b, e.ttl / e.life);
            font.draw(batch, e.text, v3.x - 60, v3.y + e.progress() * 40, 120, Align.center, false);
        }
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);
        batch.end();
    }
}

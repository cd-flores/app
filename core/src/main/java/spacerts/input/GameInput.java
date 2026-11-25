package spacerts.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import spacerts.screens.GameScreen;
import spacerts.units.Ability;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.StarSystem;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

/** Camera control, selection and order issuing. */
public class GameInput extends InputAdapter {
    public static final int[] BUILD_KEYS = {Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4,
            Input.Keys.NUM_5, Input.Keys.NUM_6, Input.Keys.NUM_7, Input.Keys.NUM_8, Input.Keys.NUM_9, Input.Keys.NUM_0,
            Input.Keys.MINUS, Input.Keys.EQUALS};
    public static final String[] BUILD_KEY_NAMES = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="};

    private final GameScreen gs;
    public final List<Unit> selected = new ArrayList<>();
    public Unit inspectUnit;
    public Body inspectBody;

    /** Placement modes: a structure chosen from a harvester's build list, or the rally flag. */
    public ShipClass placing;
    public boolean placingFlag;
    private int factoryCycle;

    public boolean dragging;
    public float dragSX, dragSY, curX, curY;
    private boolean panning, minimapDrag;
    private float lastPanX, lastPanY;
    private float lastClickTime = -10;
    private Unit lastClickUnit;
    private final Vector3 v3 = new Vector3();
    private static final Color MOVE_C = new Color(0.4f, 1f, 0.5f, 1f);
    private static final Color ATTACK_C = new Color(1f, 0.4f, 0.3f, 1f);

    public GameInput(GameScreen gs) {
        this.gs = gs;
    }

    private World w() {
        return gs.world;
    }

    public void reset() {
        selected.clear();
        inspectUnit = null;
        inspectBody = null;
        dragging = false;
        placing = null;
        placingFlag = false;
    }

    public boolean isPlacing() {
        return placing != null || placingFlag;
    }

    public void cancelPlacement() {
        placing = null;
        placingFlag = false;
    }

    /** World position of the mouse cursor. */
    public float[] cursorWorld() {
        Vector3 p = toWorld(Gdx.input.getX(), Gdx.input.getY());
        return new float[]{p.x, p.y};
    }

    public void cleanSelection() {
        selected.removeIf(u -> u.dead);
        if (inspectUnit != null && (inspectUnit.dead || !w().canSee(w().player, inspectUnit))) inspectUnit = null;
        if (inspectBody != null && inspectBody.dead) inspectBody = null;
    }

    // ------------------------------------------------------------------ camera

    public void update(float dt) {
        OrthographicCamera cam = gs.cam;
        float speed = 900f * cam.zoom * dt;
        float dx = 0, dy = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) dy += speed;
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) dy -= speed;
        if (Gdx.input.isKeyPressed(Input.Keys.A) && !ctrl() || Gdx.input.isKeyPressed(Input.Keys.LEFT)) dx -= speed;
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) dx += speed;
        int mx = Gdx.input.getX(), my = Gdx.input.getY();
        int W = Gdx.graphics.getWidth(), H = Gdx.graphics.getHeight();
        if (!dragging && !panning && !minimapDrag && Gdx.input.isCursorCatched() == false && mx >= 0 && my >= 0 && mx < W && my < H) {
            if (mx < 4) dx -= speed;
            if (mx > W - 5) dx += speed;
            if (my < 4) dy += speed;
            if (my > H - 5) dy -= speed;
        }
        // panning stays inside the system being viewed; gates, the minimap and the galaxy map move between systems
        StarSystem view = w().systemAt(cam.position.x, cam.position.y);
        cam.position.x = Math.max(view.minX(), Math.min(view.maxX(), cam.position.x + dx));
        cam.position.y = Math.max(view.minY(), Math.min(view.maxY(), cam.position.y + dy));
        cam.update();
    }

    public void moveCamera(float x, float y) {
        StarSystem s = w().systemAt(x, y);
        gs.cam.position.x = Math.max(s.minX(), Math.min(s.maxX(), x));
        gs.cam.position.y = Math.max(s.minY(), Math.min(s.maxY(), y));
        gs.cam.update();
    }

    private Vector3 toWorld(float sx, float sy) {
        return gs.cam.unproject(v3.set(sx, sy, 0));
    }

    private boolean shift() {
        return Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
    }

    private boolean ctrl() {
        return Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
    }

    // ------------------------------------------------------------------ mouse

    @Override
    public boolean touchDown(int sx, int sy, int pointer, int button) {
        curX = sx;
        curY = sy;
        if (gs.showHelp || gs.menuOpen) {
            if (button == Input.Buttons.LEFT) gs.hud.click(sx, sy);
            return true;
        }
        if (gs.showGalaxy) { // galaxy map: click to view a system, right-click to send the selection there
            if (button == Input.Buttons.LEFT && gs.hud.click(sx, sy)) return true;
            StarSystem s = gs.hud.galaxyPick(sx, sy);
            if (s == null) return true;
            if (button == Input.Buttons.LEFT) {
                moveCamera(s.cx, s.cy);
                gs.showGalaxy = false;
            } else if (button == Input.Buttons.RIGHT) {
                issueOrder(s.cx, s.cy, ctrl());
                w().notify(w().player, "Ships routed to " + s.name + " (" + s.archetype.title + ").", World.INFO);
            }
            return true;
        }
        if (isPlacing() && button == Input.Buttons.RIGHT) {
            cancelPlacement();
            return true;
        }
        if (button == Input.Buttons.LEFT && isPlacing() && !gs.hud.contains(sx, sy)) {
            float[] p;
            if (gs.hud.inMinimap(sx, sy)) p = gs.hud.minimapToWorld(sx, sy);
            else {
                Vector3 v = toWorld(sx, sy);
                p = new float[]{v.x, v.y};
            }
            confirmPlacement(p[0], p[1]);
            return true;
        }
        if (button == Input.Buttons.LEFT) {
            if (gs.hud.inMinimap(sx, sy)) {
                minimapDrag = true;
                float[] p = gs.hud.minimapToWorld(sx, sy);
                moveCamera(p[0], p[1]);
                return true;
            }
            if (gs.hud.click(sx, sy)) return true;
            dragging = true;
            dragSX = sx;
            dragSY = sy;
            return true;
        }
        if (button == Input.Buttons.RIGHT) {
            if (gs.hud.inMinimap(sx, sy)) {
                float[] p = gs.hud.minimapToWorld(sx, sy);
                issueOrder(p[0], p[1], ctrl());
                return true;
            }
            if (gs.hud.contains(sx, sy)) return true;
            Vector3 p = toWorld(sx, sy);
            issueOrder(p.x, p.y, ctrl());
            return true;
        }
        if (button == Input.Buttons.MIDDLE) {
            panning = true;
            lastPanX = sx;
            lastPanY = sy;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int sx, int sy, int pointer) {
        curX = sx;
        curY = sy;
        if (panning) {
            gs.cam.position.x -= (sx - lastPanX) * gs.cam.zoom;
            gs.cam.position.y += (sy - lastPanY) * gs.cam.zoom;
            lastPanX = sx;
            lastPanY = sy;
            moveCamera(gs.cam.position.x, gs.cam.position.y);
        }
        if (minimapDrag) {
            float[] p = gs.hud.minimapToWorld(sx, sy);
            moveCamera(p[0], p[1]);
        }
        return true;
    }

    @Override
    public boolean mouseMoved(int sx, int sy) {
        curX = sx;
        curY = sy;
        return false;
    }

    @Override
    public boolean touchUp(int sx, int sy, int pointer, int button) {
        if (button == Input.Buttons.MIDDLE) panning = false;
        if (button == Input.Buttons.LEFT) {
            minimapDrag = false;
            if (dragging) {
                dragging = false;
                if (Math.abs(sx - dragSX) < 6 && Math.abs(sy - dragSY) < 6) clickSelect(sx, sy);
                else boxSelect(dragSX, dragSY, sx, sy);
            }
        }
        return true;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (gs.hud.contains(Gdx.input.getX(), Gdx.input.getY())) return true;
        OrthographicCamera cam = gs.cam;
        Vector3 before = toWorld(Gdx.input.getX(), Gdx.input.getY()).cpy();
        cam.zoom = Math.max(0.35f, Math.min(w().galaxy.hive ? 24f : 8f, cam.zoom * (amountY > 0 ? 1.15f : 1f / 1.15f))); // the Hive's hexes are huge
        cam.update();
        Vector3 after = toWorld(Gdx.input.getX(), Gdx.input.getY());
        moveCamera(cam.position.x + before.x - after.x, cam.position.y + before.y - after.y);
        return true;
    }

    // ------------------------------------------------------------------ selection

    public Unit pickUnit(float wx, float wy, boolean ownOnly) {
        World w = w();
        Unit best = null;
        float bestR = Float.MAX_VALUE;
        float slack = 6f * gs.cam.zoom;
        for (Unit u : w.units) {
            if (u.dead || u.cls == ShipClass.DRONE) continue;
            if (ownOnly != (u.owner == w.player)) continue;
            if (!w.canSee(w.player, u)) continue;
            float d = u.dist(wx, wy);
            if (d < u.radius + slack && u.radius < bestR) {
                bestR = u.radius;
                best = u;
            }
        }
        return best;
    }

    private void clickSelect(float sx, float sy) {
        Vector3 p = toWorld(sx, sy);
        World w = w();
        Unit own = pickUnit(p.x, p.y, true);
        inspectUnit = null;
        inspectBody = null;
        if (own != null) {
            boolean dbl = own == lastClickUnit && w.time - lastClickTime < 0.4f && !own.s.stationary;
            lastClickUnit = own;
            lastClickTime = w.time;
            if (dbl) {
                selectSameOnScreen(own.cls);
                return;
            }
            if (shift()) {
                if (selected.contains(own)) selected.remove(own);
                else selected.add(own);
            } else {
                selected.clear();
                selected.add(own);
            }
            return;
        }
        if (!shift()) selected.clear();
        Unit other = pickUnit(p.x, p.y, false);
        if (other != null) {
            inspectUnit = other;
            return;
        }
        Body b = w.bodyAt(p.x, p.y, 8 * gs.cam.zoom);
        if (b != null && w.player.vision.isExplored(b.x, b.y)) {
            // double-click a jump gate: look through it into the neighbouring system
            if (b.isGate() && b.link != null && b == lastClickBody && w.time - lastClickTime < 0.4f) {
                float[] e = spacerts.systems.MovementSystem.exitPoint(w, b.link, 900, 0);
                moveCamera(e[0], e[1]);
                lastClickBody = null;
                return;
            }
            lastClickBody = b;
            lastClickTime = w.time;
            inspectBody = b;
        }
    }

    private Body lastClickBody;

    private void selectSameOnScreen(ShipClass cls) {
        selected.clear();
        OrthographicCamera cam = gs.cam;
        float hw = cam.viewportWidth * cam.zoom / 2, hh = cam.viewportHeight * cam.zoom / 2;
        for (Unit u : w().units)
            if (!u.dead && u.owner == w().player && u.cls == cls
                    && Math.abs(u.x - cam.position.x) < hw && Math.abs(u.y - cam.position.y) < hh) selected.add(u);
    }

    private void boxSelect(float sx0, float sy0, float sx1, float sy1) {
        Vector3 a = toWorld(sx0, sy0).cpy();
        Vector3 b = toWorld(sx1, sy1);
        float x0 = Math.min(a.x, b.x), x1 = Math.max(a.x, b.x), y0 = Math.min(a.y, b.y), y1 = Math.max(a.y, b.y);
        List<Unit> found = new ArrayList<>();
        boolean anyMobile = false;
        for (Unit u : w().units) {
            if (u.dead || u.owner != w().player || u.s.internal) continue;
            if (u.x + u.radius < x0 || u.x - u.radius > x1 || u.y + u.radius < y0 || u.y - u.radius > y1) continue;
            found.add(u);
            if (!u.cls.isBuilding()) anyMobile = true;
        }
        if (anyMobile) found.removeIf(u -> u.cls.isBuilding()); // dragging over the army does not grab factories or the hub
        if (!shift()) selected.clear();
        for (Unit u : found) if (!selected.contains(u)) selected.add(u);
        inspectUnit = null;
        inspectBody = null;
    }

    public Unit activeProducer() {
        for (Unit u : selected) if (!u.dead && u.owner == w().player && u.queue != null) return u;
        return null;
    }

    /** First selected harvester, used for the construction build list when no producer is selected. */
    public Unit activeBuilder() {
        if (activeProducer() != null) return null;
        for (Unit u : selected) if (!u.dead && u.owner == w().player && u.s.harvester) return u;
        return null;
    }

    /** The selected harvester best suited to build at (x, y): idle from other jobs and closest. */
    private Unit pickBuilder(float x, float y) {
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for (Unit u : selected) {
            if (u.dead || u.owner != w().player || !u.s.harvester) continue;
            float d = u.dist(x, y) + (u.buildCls != null ? 100000 : 0);
            if (d < bd) { bd = d; best = u; }
        }
        return best;
    }

    /** Chosen from a harvester's build list (hotkey or button). */
    public void startBuild(ShipClass c) {
        World w = w();
        Unit h = activeBuilder();
        if (h == null) return;
        String why = w.construction.baseBlocker(w.player, c);
        if (why == null) why = w.player.missing(c.s.energy, c.s.metal, c.s.rare);
        if (why != null) {
            w.notify(w.player, why, World.ALERT);
            return;
        }
        placingFlag = false;
        placing = c;
        String where = c == ShipClass.SOLAR ? "Left-click a star" : c == ShipClass.MINER ? "Left-click an asteroid or metal-rich planet" : "Left-click open space";
        w.notify(w.player, where
                + " to build " + c.s.name + ". Right-click or Esc cancels.", World.INFO);
    }

    private void confirmPlacement(float wx, float wy) {
        World w = w();
        if (placingFlag) {
            w.player.hasFlag = true;
            w.player.flagX = w.clampX(wx, wy);
            w.player.flagY = w.clampY(wx, wy);
            placingFlag = false;
            w.ring(wx, wy, 60 * gs.cam.zoom, w.player.color, 0.5f);
            w.notify(w.player, "Rally flag placed. New warships will gather here (Shift+F removes it).", World.GOOD);
            return;
        }
        ShipClass c = placing;
        Body body = null;
        if (c.targetsBody()) body = w.bodyAt(wx, wy, 30 * gs.cam.zoom);
        Unit h = pickBuilder(wx, wy);
        if (h == null) {
            cancelPlacement();
            return;
        }
        String err = w.construction.order(h, c, body, wx, wy);
        if (err != null) {
            w.notify(w.player, err, World.ALERT);
            return; // stay in placement mode so the player can pick another spot
        }
        w.ring(c.targetsBody() && body != null ? body.x : wx, c.targetsBody() && body != null ? body.y : wy,
                60 * gs.cam.zoom, MOVE_C, 0.35f);
        if (!shift()) placing = null; // hold Shift to queue more with other selected harvesters
    }

    // ------------------------------------------------------------------ orders

    private void issueOrder(float wx, float wy, boolean attackMove) {
        World w = w();
        List<Unit> movers = new ArrayList<>();
        boolean any = false;
        for (Unit u : selected) {
            if (u.dead || u.owner != w.player) continue;
            any = true;
            if (u.s.stationary || (u.queue != null && shift())) { // Shift+right-click on a producer: set its rally point
                if (u.queue != null) {
                    u.hasRally = true;
                    u.rallyX = wx;
                    u.rallyY = wy;
                }
                continue;
            }
            u.clearAuto(); // the player takes over from automation
            u.fleeTimer = 0;
            movers.add(u);
        }
        if (!any) return;
        Unit enemy = pickUnit(wx, wy, false);
        Body body = w.bodyAt(wx, wy, 10 * gs.cam.zoom);
        if (body != null && !w.player.vision.isExplored(body.x, body.y)) body = null;
        if (enemy == null && body != null && body.isGate() && body.link != null) {
            // right-click a jump gate: go through it and gather just past the far gate
            float[] e = spacerts.systems.MovementSystem.exitPoint(w, body.link, 380, 0);
            wx = e[0];
            wy = e[1];
            body = null;
        }

        int n = movers.size();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
        int rows = (n + cols - 1) / cols;
        float spacing = 34;
        for (Unit u : movers) spacing = Math.max(spacing, u.radius * 2.6f);
        boolean attacked = false;
        for (int i = 0; i < n; i++) {
            Unit u = movers.get(i);
            if (enemy != null && u.armed()) {
                u.order = Order.ATTACK;
                u.target = enemy;
                u.body = null;
                attacked = true;
                continue;
            }
            if (body != null) {
                if (u.s.harvester && body.harvestable()) {
                    w.economy.assign(u, body);
                    continue;
                }
                if (u.s.colonizer && body.habitable()) {
                    u.command(Order.COLONIZE, body.x, body.y);
                    u.body = body;
                    continue;
                }
                if (u.s.surveyor && body.type != null && body.type.rare != null) {
                    u.command(Order.SURVEY, body.x, body.y);
                    u.body = body;
                    continue;
                }
            }
            int col = i % cols, row = i / cols;
            float ox = (col - (cols - 1) / 2f) * spacing, oy = ((rows - 1) / 2f - row) * spacing;
            u.command(attackMove ? Order.ATTACK_MOVE : Order.MOVE, wx + ox, wy + oy);
        }
        w.ring(wx, wy, 45 * gs.cam.zoom, attacked || attackMove ? ATTACK_C : MOVE_C, 0.35f);
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean keyDown(int key) {
        World w = w();
        if (gs.showHelp) {
            if (key == Input.Keys.F1 || key == Input.Keys.ESCAPE || key == Input.Keys.ENTER || key == Input.Keys.SPACE) gs.showHelp = false;
            return true;
        }
        if (w.gameOver && key == Input.Keys.ENTER) {
            gs.toMenu();
            return true;
        }
        if (gs.menuOpen) {
            if (key == Input.Keys.ESCAPE) gs.menuOpen = false;
            else if (key == Input.Keys.M) gs.toMenu();
            else if (key == Input.Keys.F5) gs.save();
            else if (key == Input.Keys.F9) gs.load();
            return true;
        }
        for (int i = 0; i < BUILD_KEYS.length; i++) {
            if (key == BUILD_KEYS[i]) {
                Body colony = selectedColony();
                if (colony != null) {
                    if (i < PLANET_UPGRADES.length) upgradePlanet(colony, PLANET_UPGRADES[i]);
                    return true;
                }
                Unit p = activeProducer();
                if (p != null) {
                    ShipClass[] list = p.cls.buildList();
                    if (i < list.length) {
                        String err = w.production.enqueue(p, list[i]);
                        if (err != null) w.notify(w.player, err, World.ALERT);
                    }
                } else {
                    Unit h = activeBuilder();
                    if (h != null) {
                        ShipClass[] list = h.cls.buildList();
                        if (i < list.length) startBuild(list[i]);
                    }
                }
                return true;
            }
        }
        switch (key) {
            case Input.Keys.F1: gs.showHelp = true; return true;
            case Input.Keys.SPACE: gs.paused = !gs.paused; return true;
            case Input.Keys.LEFT_BRACKET: gs.speed = Math.max(1, gs.speed - 1); return true;
            case Input.Keys.RIGHT_BRACKET: gs.speed = Math.min(4, gs.speed + 1); return true;
            case Input.Keys.F5: gs.save(); return true;
            case Input.Keys.F9: gs.load(); return true;
            case Input.Keys.F:
                if (shift()) {
                    w.player.hasFlag = false;
                    placingFlag = false;
                    w.notify(w.player, "Rally flag removed.", World.INFO);
                } else {
                    placing = null;
                    placingFlag = true;
                    w.notify(w.player, "Left-click (map or minimap) to place the rally flag. Right-click or Esc cancels.", World.INFO);
                }
                return true;
            case Input.Keys.G:
                w.player.autoDefense = !w.player.autoDefense;
                w.notify(w.player, "Auto-defense " + (w.player.autoDefense ? "ON: idle ships defend your bases and harvesters flee raids."
                        : "OFF: your units only do what you tell them."), World.INFO);
                return true;
            case Input.Keys.F2: {
                List<Unit> facs = new ArrayList<>();
                for (Unit u : w.units) if (!u.dead && u.owner == w.player && u.cls == ShipClass.FACTORY) facs.add(u);
                if (facs.isEmpty()) {
                    w.notify(w.player, "No Ship Factory yet - select a Harvester and build one (key 1).", World.INFO);
                    return true;
                }
                Unit fac = facs.get(factoryCycle++ % facs.size());
                selected.clear();
                selected.add(fac);
                moveCamera(fac.x, fac.y);
                return true;
            }
            case Input.Keys.TAB:
                if (w.galaxy.hive) gs.showGalaxy = !gs.showGalaxy;
                return true;
            case Input.Keys.J: // jump the camera to the latest alert
                if (w.time - w.alertTime < 60) moveCamera(w.alertX, w.alertY);
                return true;
            case Input.Keys.ESCAPE:
                if (gs.showGalaxy) gs.showGalaxy = false;
                else if (isPlacing()) cancelPlacement();
                else if (!selected.isEmpty() || inspectUnit != null || inspectBody != null) reset();
                else gs.menuOpen = true;
                return true;
            case Input.Keys.Q: useAbilities(); return true;
            case Input.Keys.U: { // queue the next tier upgrade on the selected Mothership / Ship Factory
                Unit p = activeProducer();
                if (p == null) return true;
                for (ShipClass c : p.cls.buildList())
                    if (c.isUpgrade() && c.s.upgradeTo == p.tier + 1) {
                        String err = w.production.enqueue(p, c);
                        w.notify(w.player, err != null ? err : c.s.name + " started.", err != null ? World.ALERT : World.INFO);
                        return true;
                    }
                w.notify(w.player, p.s.name + " can't be upgraded further.", World.INFO);
                return true;
            }
            case Input.Keys.R: toggleRetreat(); return true;
            case Input.Keys.X:
                for (Unit u : selected) if (u.owner == w.player && !u.s.stationary) {
                    u.idle();
                    u.clearAuto();
                }
                return true;
            case Input.Keys.H: {
                Unit ms = w.player.mothership;
                if (ms != null && !ms.dead) {
                    selected.clear();
                    selected.add(ms);
                    moveCamera(ms.x, ms.y);
                }
                return true;
            }
            case Input.Keys.C:
                if (!selected.isEmpty()) {
                    float cx = 0, cy = 0;
                    for (Unit u : selected) { cx += u.x; cy += u.y; }
                    moveCamera(cx / selected.size(), cy / selected.size());
                }
                return true;
            case Input.Keys.I:
                selected.clear();
                for (Unit u : w.units) if (!u.dead && u.owner == w.player && u.s.harvester && u.order == Order.IDLE) selected.add(u);
                if (selected.isEmpty()) w.notify(w.player, "No idle harvesters.", World.INFO);
                return true;
            case Input.Keys.B: {
                Unit hub = w.player.hub;
                if (hub != null && !hub.dead) {
                    selected.clear();
                    selected.add(hub);
                    moveCamera(hub.x, hub.y);
                } else w.notify(w.player, "No Research Hub yet - select a Harvester and build one (key 2).", World.INFO);
                return true;
            }
            case Input.Keys.A:
                if (ctrl()) {
                    selected.clear();
                    for (Unit u : w.units) if (!u.dead && u.owner == w.player && u.cls.isWarship()) selected.add(u);
                }
                return true;
            default:
                return false;
        }
    }

    /** Upgrades offered on a selected colony, in hotkey order (1, 2). */
    public static final ShipClass[] PLANET_UPGRADES = {ShipClass.DEF_TURRET, ShipClass.DEF_SHIELD};

    /** The player's colony planet when exactly that colony is selected (or inspected), else null. */
    public Body selectedColony() {
        World w = w();
        if (selected.size() == 1) {
            Unit u = selected.get(0);
            if (!u.dead && u.owner == w.player && u.cls == ShipClass.COLONY && u.home != null) return u.home;
            return null;
        }
        if (selected.isEmpty() && inspectBody != null && inspectBody.colonized() && inspectBody.colony.owner == w.player)
            return inspectBody;
        return null;
    }

    /** Sends the nearest free harvester to build a turret or shield upgrade on one of the player's colonies. */
    public void upgradePlanet(Body colony, ShipClass c) {
        World w = w();
        String why = w.construction.baseBlocker(w.player, c);
        if (why == null) why = w.player.missing(c.s.energy, c.s.metal, c.s.rare);
        Unit h = null;
        if (why == null) {
            h = w.construction.nearestFreeBuilder(w.player, colony);
            if (h == null) why = "No free harvester to build the upgrade";
        }
        if (why == null) why = w.construction.order(h, c, colony, colony.x, colony.y);
        if (why != null) {
            w.notify(w.player, why, World.ALERT);
            return;
        }
        w.notify(w.player, "A harvester is on its way to build a " + c.s.name + " on " + colony.name + ".", World.INFO);
        w.ring(colony.x, colony.y, colony.radius * 2f, MOVE_C, 0.35f);
    }

    /** R: switches auto-retreat on or off for the selected warships (all on unless every one is already on). */
    public void toggleRetreat() {
        World w = w();
        boolean anyOff = false, any = false;
        for (Unit u : selected)
            if (!u.dead && u.owner == w.player && u.cls.isWarship()) {
                any = true;
                if (u.retreatOff) anyOff = true;
            }
        if (!any) return;
        for (Unit u : selected)
            if (!u.dead && u.owner == w.player && u.cls.isWarship()) u.retreatOff = !anyOff;
        w.notify(w.player, anyOff ? "Auto-retreat ON: these ships pull back to repair below 25% hull."
                : "Auto-retreat OFF: these ships fight to the end.", World.INFO);
    }

    private void useAbilities() {
        World w = w();
        String firstErr = null;
        boolean anyOk = false;
        java.util.Set<ShipClass> used = new java.util.HashSet<>();
        for (Unit u : selected) {
            if (u.dead || u.owner != w.player || u.s.ability == Ability.NONE) continue;
            // Ping: one per keypress is enough
            if (u.s.ability == Ability.PING && used.contains(u.cls)) continue;
            String err = w.behavior.useAbility(u);
            if (err == null) {
                anyOk = true;
                used.add(u.cls);
            } else if (firstErr == null) firstErr = err;
        }
        if (!anyOk && firstErr != null) w.notify(w.player, firstErr, World.INFO);
    }
}
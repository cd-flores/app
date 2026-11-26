package spacerts.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import spacerts.input.GameInput;
import spacerts.research.RareItem;
import spacerts.research.Tech;
import spacerts.screens.GameScreen;
import spacerts.systems.EconomySystem;
import spacerts.systems.ProductionSystem;
import spacerts.systems.UnitBehavior;
import spacerts.units.Ability;
import spacerts.units.ShipClass;
import spacerts.units.Spec;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.Message;
import spacerts.world.StarSystem;
import spacerts.world.VisionGrid;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/* Immediate-mode HUD: resource bar, info panel, build and research panels, minimap, overlays. */
public class Hud {
    public static final float TOP = 30, BOTTOM = 184, MINI = 170;

    private static final Color PANEL = new Color(0.02f, 0.04f, 0.09f, 0.93f);
    private static final Color ACCENT = new Color(0.2f, 0.45f, 0.8f, 0.7f);
    private static final Color ENERGY_C = new Color(1f, 0.85f, 0.35f, 1f);
    private static final Color METAL_C = new Color(0.72f, 0.78f, 0.88f, 1f);
    private static final Color DIM = new Color(0.55f, 0.6f, 0.7f, 1f);
    private static final Color GRAY = new Color(0.45f, 0.45f, 0.5f, 1f);
    private static final Color TITLE = new Color(0.55f, 0.85f, 1f, 1f);

    private final GameScreen gs;
    private final List<Btn> buttons = new ArrayList<>();
    private final List<Txt> texts = new ArrayList<>();
    private final GlyphLayout layout = new GlyphLayout();
    private String tooltip;
    private float mx, my, W, H;
    private boolean rpVisible;
    private float rpX, rpY, rpW, rpH;

    private static class Btn {
        final float x, y, w, h;
        final Runnable action;

        Btn(float x, float y, float w, float h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }

        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private static class Txt {
        float x, y, w, scale;
        String s;
        final Color c = new Color();
        int align;
        boolean wrap;
    }

    public Hud(GameScreen gs) {
        this.gs = gs;
    }

    public boolean contains(float sx, float syDown) {
        float h = Gdx.graphics.getHeight();
        float y = h - syDown;
        if (y > h - TOP || y < BOTTOM) return true;
        return rpVisible && sx >= rpX && sx <= rpX + rpW && y >= rpY && y <= rpY + rpH;
    }

    public boolean click(float sx, float syDown) {
        float y = Gdx.graphics.getHeight() - syDown;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Btn b = buttons.get(i);
            if (b.contains(sx, y)) {
                b.action.run();
                return true;
            }
        }
        if (gs.showHelp) return true; // the tutorial pages are turned with its buttons
        if (gs.menuOpen) return true;
        return contains(sx, syDown);
    }

    public boolean inMinimap(float sx, float syDown) {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float y = h - syDown;
        float x0 = w - MINI - 8, y0 = 7;
        return sx >= x0 && sx <= x0 + MINI && y >= y0 && y <= y0 + MINI;
    }

    public float[] minimapToWorld(float sx, float syDown) {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float y = h - syDown;
        float x0 = w - MINI - 8, y0 = 7;
        miniFrame(gs.world);
        float wx = miniOx + (sx - x0) / miniScale, wy = miniOy + (y - y0) / miniScale;
        StarSystem cs = gs.world.systemAt(gs.cam.position.x, gs.cam.position.y);
        return new float[]{cs.clampX(wx, wy), cs.clampY(wx, wy)};
    }

    private Txt txt(float x, float y, String s, Color c, float scale) {
        Txt t = new Txt();
        t.x = x;
        t.y = y;
        t.s = s;
        t.c.set(c);
        t.scale = scale;
        t.align = Align.left;
        texts.add(t);
        return t;
    }

    private Txt wrap(float x, float y, float w, String s, Color c, float scale) {
        Txt t = txt(x, y, s, c, scale);
        t.w = w;
        t.wrap = true;
        return t;
    }

    private Txt aligned(float x, float y, float w, String s, Color c, float scale, int align) {
        Txt t = txt(x, y, s, c, scale);
        t.w = w;
        t.align = align;
        return t;
    }

    private float wrappedHeight(String s, float scale, float w) {
        BitmapFont f = gs.game.font;
        f.getData().setScale(scale);
        layout.setText(f, s, Color.WHITE, w, Align.left, true);
        f.getData().setScale(1f);
        return layout.height;
    }

    private float width(String s, float scale) {
        BitmapFont f = gs.game.font;
        f.getData().setScale(scale);
        layout.setText(f, s);
        f.getData().setScale(1f);
        return layout.width;
    }

    private void flushTexts() {
        SpriteBatch batch = gs.game.batch;
        BitmapFont f = gs.game.font;
        batch.setProjectionMatrix(gs.hudCam.combined);
        batch.begin();
        for (Txt t : texts) {
            f.getData().setScale(t.scale);
            f.setColor(t.c);
            if (t.w > 0) f.draw(batch, t.s, t.x, t.y, t.w, t.align, t.wrap);
            else f.draw(batch, t.s, t.x, t.y);
        }
        f.getData().setScale(1f);
        f.setColor(Color.WHITE);
        batch.end();
        texts.clear();
    }

    /** Scale that makes s fit within maxW (never larger than scale). */
    private float fit(String s, float scale, float maxW) {
        float wv = width(s, scale);
        return wv > maxW ? scale * maxW / wv : scale;
    }

    private boolean hovered(float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void border(ShapeRenderer sr, float x, float y, float w, float h, Color c) {
        sr.setColor(c);
        sr.rect(x, y, w, 1);
        sr.rect(x, y + h - 1, w, 1);
        sr.rect(x, y, 1, h);
        sr.rect(x + w - 1, y, 1, h);
    }

    private static String clock(float t) {
        int s = (int) t;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    public void render() {
        W = Gdx.graphics.getWidth();
        H = Gdx.graphics.getHeight();
        mx = Gdx.input.getX();
        my = H - Gdx.input.getY();
        World w = gs.world;
        Faction p = w.player;
        buttons.clear();
        texts.clear();
        tooltip = null;
        rpVisible = false;

        ShapeRenderer sr = gs.game.shapes;
        OrthographicCamera hc = gs.hudCam;
        sr.setProjectionMatrix(hc.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        sr.begin(ShapeRenderer.ShapeType.Filled);
        if (gs.showGalaxy && w.galaxy.hive) galaxyMap(sr, w);
        topBar(sr, w, p);
        bottomPanel(sr, w, p);
        tacticToggle(sr, w, p);
        minimap(sr, w);
        messages(w);
        if (gs.input.chatOpen) chatBox(sr, w);
        sr.end();
        flushTexts();

        if (gs.showHelp || gs.menuOpen || gs.confirmSurrender || w.gameOver || victoryShowing()) {
            buttons.clear();
            sr.begin(ShapeRenderer.ShapeType.Filled);
            if (gs.showHelp) help(sr);
            else if (w.gameOver) gameOver(sr, w);
            else if (gs.confirmSurrender) surrenderConfirm(sr);
            else if (victoryShowing() && !gs.menuOpen) victory(sr, w);
            else pauseMenu(sr);
            sr.end();
            flushTexts();
            tooltip = null;
        }
        if (tooltip != null) drawTooltip(sr);
    }

    private void topBar(ShapeRenderer sr, World w, Faction p) {
        sr.setColor(PANEL);
        sr.rect(0, H - TOP, W, TOP);
        sr.setColor(ACCENT);
        sr.rect(0, H - TOP - 1, W, 1);
        float y = H - 9;

        // ---- left: stockpiles, housing, upkeep, rare items and the auto-defense button
        int tier = spacerts.systems.EconomySystem.upkeepTier(p);
        float drain = spacerts.systems.EconomySystem.upkeep(p);
        List<String> labels = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        labels.add("Energy " + (int) p.energy);
        colors.add(ENERGY_C);
        labels.add("Metal " + (int) p.metal);
        colors.add(METAL_C);
        labels.add("Housing " + p.popUsed + "/" + p.popCap);
        colors.add(p.popUsed >= p.popCap ? World.ALERT : Color.WHITE);
        labels.add(tier == 0 ? "No upkeep" : (tier == 1 ? "Low upkeep -" : "High upkeep -") + String.format("%.1f/s", drain));
        colors.add(tier == 0 ? UPKEEP_NONE : tier == 1 ? UPKEEP_LOW : World.ALERT);
        int firstRare = labels.size();
        for (RareItem r : RareItem.values())
            if (!r.scarce() || p.found[r.ordinal()] || p.rare[r.ordinal()] > 0) { // scarce finds appear once found
                labels.add(r.shortName + " " + p.rare[r.ordinal()]);
                colors.add(r.color);
            }
        if (p.cheatsUsed) { // a cheater's badge, as in Warcraft III's "Cheat Code Enabled"
            labels.add("CHEATS");
            colors.add(World.ALERT);
        }
        String ad = "Auto-defend " + (p.autoDefense ? "ON" : "OFF") + " (G)";

        // ---- right: status segments, most important first (dropped from the end until everything fits)
        float toRaid = Math.max(0, w.threats.nextWave - w.time);
        String[] seg = new String[9];
        int[] rank = new int[9]; // display order left to right
        int n = 0;
        StarSystem here = w.systemAt(gs.cam.position.x, gs.cam.position.y);
        seg[n] = "Time " + clock(w.time); rank[n++] = 4;
        seg[n] = "Pirate raid ~" + clock(toRaid); rank[n++] = 5;
        seg[n] = "Speed x" + gs.speed + (gs.paused ? "  PAUSED" : ""); rank[n++] = 6;
        if (w.piratesOnly()) {
            seg[n] = "Conquered " + spacerts.systems.ConquestSystem.ownedCount(w) + "/" + w.galaxy.systems.size()
                    + "  Pirate bases " + spacerts.world.PirateBases.livingBases(w).size() + (w.ascended ? "  ASCENDED" : "");
            rank[n++] = 3;
        }
        seg[n] = "F1 Help"; rank[n++] = 8;
        seg[n] = w.difficulty.title; rank[n++] = 2;
        if (w.galaxy.hive) { seg[n] = here.name + " (" + here.archetype.title + ")"; rank[n++] = 1; }
        seg[n] = w.galaxy.hive ? "Tab Galaxy map" : "F Rally flag" + (p.hasFlag ? " (set)" : ""); rank[n++] = 7;

        // pick the largest scale and the most segments that fit without overlapping
        float scale = 1f;
        int keep = n;
        String right = "";
        float leftEnd = 0;
        for (float s = 1f; s >= 0.7f; s -= 0.1f) {
            scale = s;
            leftEnd = 12;
            for (int i = 0; i < labels.size(); i++) leftEnd += width(labels.get(i), s) + 22 * s + (i == firstRare ? 12 * s : 0);
            leftEnd += width(ad, 0.85f * s) + 14;
            float room = W - leftEnd - 24;
            for (keep = n; keep >= 3; keep--) {
                right = joinSegments(seg, rank, keep);
                if (width(right, s) <= room) break;
            }
            if (keep >= 3 && width(right, s) <= room) break;
        }

        float x = 12;
        for (int i = 0; i < labels.size(); i++) {
            if (i == firstRare) x += 12 * scale;
            float x0 = x;
            txt(x, y, labels.get(i), colors.get(i), scale);
            x += width(labels.get(i), scale) + 22 * scale;
            if (i == 3 && hovered(x0, H - TOP, x - x0 - 10, TOP))
                tooltip = "Upkeep: ships cost their housing and each station (Ship Station, Hub, Repair Station) counts as "
                        + spacerts.systems.EconomySystem.UPKEEP_PER_STATION + ".\n"
                        + "Up to " + spacerts.systems.EconomySystem.UPKEEP_LOW + ": free.   "
                        + (spacerts.systems.EconomySystem.UPKEEP_LOW + 1) + "-" + spacerts.systems.EconomySystem.UPKEEP_HIGH + ": "
                        + spacerts.systems.EconomySystem.UPKEEP_LOW_RATE + " energy/s each.   Over " + spacerts.systems.EconomySystem.UPKEEP_HIGH + ": "
                        + spacerts.systems.EconomySystem.UPKEEP_HIGH_RATE + " energy/s each.\n"
                        + "You have " + p.upkeepPoints + " upkeep: -" + String.format("%.1f", drain) + " energy/s.\n"
                        + "Repairs cost metal (a full repair is about half a ship's metal price): currently -"
                        + String.format("%.1f", p.repairSpend) + " metal/s.";
        }

        // auto-defense toggle (also G)
        float bw = width(ad, 0.85f * scale) + 14, bx = x, by = H - TOP + 3, bh = TOP - 6;
        boolean hov = hovered(bx, by, bw, bh);
        if (p.autoDefense) sr.setColor(hov ? 0.15f : 0.1f, hov ? 0.4f : 0.3f, hov ? 0.22f : 0.16f, 1f);
        else sr.setColor(hov ? 0.4f : 0.3f, hov ? 0.15f : 0.1f, hov ? 0.15f : 0.1f, 1f);
        sr.rect(bx, by, bw, bh);
        txt(bx + 7, y, ad, Color.WHITE, 0.85f * scale);
        buttons.add(new Btn(bx, by, bw, bh, gs.input::toggleAutoDefense));
        if (hov) tooltip = "Auto-defense: idle warships attack enemies near your bases (about 1.5x their strength),\n"
                + "harvesters flee from raids, and your ships focus fire. (Auto-retreat is separate: R on selected warships.)\n"
                + "Ships you are giving orders to are never overridden.";

        float rx = bx + bw + 24;
        aligned(rx, y, W - 12 - rx, right, toRaid < 45 ? World.ALERT : DIM, scale, Align.right);
    }

    private static final Color UPKEEP_NONE = new Color(0.5f, 0.9f, 0.55f, 1f), UPKEEP_LOW = new Color(1f, 0.85f, 0.35f, 1f);

    /** The `keep` most important segments, shown in their display order (rank). */
    private static String joinSegments(String[] seg, int[] rank, int keep) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= 8; r++)
            for (int i = 0; i < keep; i++)
                if (rank[i] == r) sb.append(sb.length() > 0 ? "     " : "").append(seg[i]);
        return sb.toString();
    }

    private void bottomPanel(ShapeRenderer sr, World w, Faction p) {
        sr.setColor(PANEL);
        sr.rect(0, 0, W, BOTTOM);
        sr.setColor(ACCENT);
        sr.rect(0, BOTTOM, W, 1);
        sr.setColor(0.15f, 0.25f, 0.4f, 0.6f);
        sr.rect(306, 8, 1, BOTTOM - 16);

        infoPanel(sr, w, p, 12, BOTTOM - 10, 284);

        Unit prod = gs.input.activeProducer();
        Unit builder = gs.input.activeBuilder();
        float x0 = 318, x1 = W - MINI - 22;
        if (prod != null) {
            buildPanel(sr, w, p, prod, x0, x1);
            if (prod.cls == ShipClass.HUB) researchPanel(sr, w, p);
            if (prod.cls == ShipClass.COMMAND) tacticSelect(sr, p);
        } else if (builder != null) {
            constructPanel(sr, w, p, builder, x0, x1);
        } else if (gs.input.selectedColony() != null) {
            colonyPanel(sr, w, p, gs.input.selectedColony(), x0, x1);
        } else {
            float y = BOTTOM - 14;
            txt(x0, y, "Commands", TITLE, 1f);
            float bx = retreatToggle(sr, w, p, x0 + width("Commands", 1f) + 20, y);
            bx = abilityButtons(sr, p, bx, y, x1);
            targetingButton(sr, p, bx, y, x1);
            String hint = "Left-click / drag: select     Right-click: move, attack, harvest, colonize, survey     Ctrl+Right-click: attack-move\n"
                    + "H: Mothership (harvesters)   F2: Ship Stations (warships)   V: rally stations   B: Research Hub   I: idle harvesters   Ctrl+A: all warships\n"
                    + "F: place rally flag (Shift+F removes)   Skills: Q Ping  E Ghost Drive  T Taunt  Z Dash  N Nova  O Overcharge   R: auto-retreat on/off   X: stop   C: center   Double-click: select all of a type\n"
                    + "Select a Harvester to build Stations, the Hub and Repair Stations. Click a colony to upgrade it.   Space: pause   [ ]: speed   F5 / F9: save / load";
            wrap(x0, y - 26, x1 - x0, hint, DIM, 0.85f);
        }
    }

    /** One button per skill the selected ships have, labelled with its own hotkey and the shortest cooldown left. */
    private float abilityButtons(ShapeRenderer sr, Faction p, float bx, float y, float x1) {
        for (Ability a : Ability.values()) {
            if (a == Ability.NONE) continue;
            int have = 0;
            float cd = Float.MAX_VALUE;
            for (Unit u : gs.input.selected)
                if (!u.dead && u.owner == p && u.s.has(a)) {
                    have++;
                    cd = Math.min(cd, u.abilityCds[a.ordinal()]);
                }
            if (have == 0) continue;
            String label = "[" + a.keyName + "] " + a.title + (cd > 0 ? " " + (int) Math.ceil(cd) + "s" : "");
            float bw = width(label, 0.85f) + 14, bh = 20, by = y - 16;
            if (bx + bw > x1) return bx;
            boolean hov = hovered(bx, by, bw, bh);
            boolean ready = cd <= 0;
            sr.setColor(ready ? (hov ? 0.2f : 0.13f) : 0.1f, ready ? (hov ? 0.32f : 0.22f) : 0.12f, ready ? (hov ? 0.55f : 0.4f) : 0.18f, 1f);
            sr.rect(bx, by, bw, bh);
            txt(bx + 7, y, label, ready ? Color.WHITE : DIM, 0.85f);
            final Ability fa = a;
            buttons.add(new Btn(bx, by, bw, bh, () -> gs.input.useAbility(fa)));
            if (hov) tooltip = skillHelp(a);
            bx += bw + 8;
        }
        return bx;
    }

    /** "Target: Nearest (K)" for the selected warships; clicking cycles the targeting mode. */
    private void targetingButton(ShapeRenderer sr, Faction p, float bx, float y, float x1) {
        spacerts.units.Targeting mode = null;
        boolean mixed = false;
        for (Unit u : gs.input.selected) {
            if (u.dead || u.owner != p || !(u.cls.isWarship() || (u.armed() && !u.s.stationary))) continue;
            if (mode == null) mode = u.targeting;
            else if (mode != u.targeting) mixed = true;
        }
        if (mode == null) return;
        String label = "Target: " + (mixed ? "Mixed" : mode.title) + " (K)";
        float bw = width(label, 0.85f) + 14, bh = 20, by = y - 16;
        if (bx + bw > x1) return;
        boolean hov = hovered(bx, by, bw, bh);
        sr.setColor(hov ? 0.32f : 0.22f, hov ? 0.26f : 0.18f, hov ? 0.1f : 0.06f, 1f);
        sr.rect(bx, by, bw, bh);
        txt(bx + 7, y, label, Color.WHITE, 0.85f);
        buttons.add(new Btn(bx, by, bw, bh, gs.input::cycleTargeting));
        if (hov) tooltip = "Targeting (K cycles): which enemy these ships pick among those in reach.\n"
                + "Nearest - Farthest - Most health - Least health - Random. Taunts still win.";
    }

    /** The tactic toggle on top of the bottom bar, right-aligned above the minimap side. */
    private void tacticToggle(ShapeRenderer sr, World w, Faction p) {
        if (p.tactics.isEmpty() || p.activeTactic == null) return;
        boolean cc = spacerts.net.CommandExecutor.hasCommandCenter(w, p);
        boolean on = gs.tacticsOn;
        String label = "Tactic: " + p.activeTactic.title + (cc ? (on ? "  ON" : "  OFF") : "  (needs a Command Center)") + "  (Y)";
        float bw = width(label, 0.9f) + 18, bh = 24, bx = W - MINI - 22 - bw, by = BOTTOM + 4;
        boolean hov = hovered(bx, by, bw, bh);
        if (!cc) sr.setColor(0.12f, 0.12f, 0.14f, 0.95f);
        else if (on) sr.setColor(hov ? 0.5f : 0.4f, hov ? 0.3f : 0.22f, hov ? 0.08f : 0.05f, 0.95f);
        else sr.setColor(hov ? 0.18f : 0.1f, hov ? 0.22f : 0.14f, hov ? 0.32f : 0.22f, 0.95f);
        sr.rect(bx, by, bw, bh);
        border(sr, bx, by, bw, bh, on && cc ? PAGE_C : ACCENT);
        txt(bx + 9, by + 17, label, cc ? Color.WHITE : DIM, 0.9f);
        buttons.add(new Btn(bx, by, bw, bh, gs.input::toggleTactics));
        if (hov) {
            StringBuilder sb = new StringBuilder(p.activeTactic.title + ": " + p.activeTactic.desc);
            for (spacerts.units.TacticUpgrade u : p.tacticUps)
                if (u.needs == null || u.needs == p.activeTactic) sb.append("\n+ ").append(u.title).append(": ").append(u.desc);
            sb.append("\nWhile ON, attack orders (Ctrl+right-click, or right-click an enemy) form the selected ships up first.\n"
                    + "Ships the tactic has no place for just attack-move. Pick the tactic at the Command Center.");
            tooltip = sb.toString();
        }
    }

    /** Command Center selected: buttons to pick the active tactic (locked ones greyed out). */
    private void tacticSelect(ShapeRenderer sr, Faction p) {
        float bw = 150, bh = 26, gap = 6, x = 318, y = BOTTOM + 8;
        txt(x, y + 18, "Active tactic:", TITLE, 0.9f);
        x += width("Active tactic:", 0.9f) + 10;
        for (spacerts.units.Tactic t : spacerts.units.Tactic.values()) {
            boolean have = p.tactics.contains(t), active = p.activeTactic == t;
            boolean hov = have && hovered(x, y, bw, bh);
            if (active) sr.setColor(0.4f, 0.3f, 0.08f, 0.95f);
            else if (have) sr.setColor(hov ? 0.2f : 0.12f, hov ? 0.3f : 0.2f, hov ? 0.5f : 0.34f, 0.95f);
            else sr.setColor(0.07f, 0.07f, 0.09f, 0.9f);
            sr.rect(x, y, bw, bh);
            border(sr, x, y, bw, bh, active ? PAGE_C : ACCENT);
            aligned(x, y + 18, bw, (active ? "> " : "") + t.title + (have ? "" : " (locked)"), have ? Color.WHITE : DIM, 0.85f, Align.center);
            final spacerts.units.Tactic ft = t;
            if (have) buttons.add(new Btn(x, y, bw, bh, () -> gs.issue(new spacerts.net.Command(spacerts.net.Command.Type.SET_TACTIC, 0).arg(ft.ordinal()))));
            if (hovered(x, y, bw, bh)) tooltip = t.title + ": " + t.desc + (have ? "" : "\nUnlock it in this Command Center's build list.");
            x += bw + gap;
        }
    }

    private static String skillHelp(Ability a) {
        switch (a) {
            case PING: return "Sensor Ping (Q, Wisp): reveals everything within 1600 for 8 s, cloaked ships included.";
            case GHOST: return "Ghost Drive (E, Wisp): cloaked and " + (int) ((UnitBehavior.GHOST_SPEED - 1) * 100) + "% faster for "
                    + (int) UnitBehavior.GHOST_TIME + " s, but it can't fire. Detectors still see it. Cooldown " + (int) UnitBehavior.GHOST_CD + " s.";
            case DASH: return "Evasive Dash (Z, Longbow / Phantom): leaps " + (int) UnitBehavior.DASH_DIST + " away from the nearest threat\n"
                    + "(or straight backward). Cooldown " + (int) UnitBehavior.DASH_CD + " s.";
            case TAUNT: return "Taunt (T): nearby enemies must shoot this ship for 5 s.";
            case NOVA: return "Nova Cannon (N, Leviathan): massive area blast at its target. Needs Nova Cannon research.";
            case OVERCHARGE: return "Overcharge (O, Sovereign): double rate of fire for 6 s.";
            default: return "";
        }
    }

    /** "Auto-retreat ON/OFF (R)" button for the selected warships; returns where the next button can go. */
    private float retreatToggle(ShapeRenderer sr, World w, Faction p, float bx, float y) {
        int ships = 0, off = 0, retreating = 0;
        for (Unit u : gs.input.selected)
            if (!u.dead && u.owner == p && u.cls.isWarship()) {
                ships++;
                if (u.retreatOff) off++;
                if (u.retreating) retreating++;
            }
        if (ships == 0) return bx;
        boolean on = off == 0;
        String label = "Auto-retreat " + (on ? "ON" : off == ships ? "OFF" : "MIXED") + " (R)" + (retreating > 0 ? "  - " + retreating + " repairing" : "");
        float bw = width(label, 0.85f) + 14, bh = 20, by = y - 16;
        boolean hov = hovered(bx, by, bw, bh);
        if (on) sr.setColor(hov ? 0.15f : 0.1f, hov ? 0.4f : 0.3f, hov ? 0.22f : 0.16f, 1f);
        else sr.setColor(hov ? 0.4f : 0.3f, hov ? 0.15f : 0.1f, hov ? 0.15f : 0.1f, 1f);
        sr.rect(bx, by, bw, bh);
        txt(bx + 7, y, label, Color.WHITE, 0.85f);
        buttons.add(new Btn(bx, by, bw, bh, gs.input::toggleRetreat));
        if (hov) tooltip = "Auto-retreat: below " + (int) (Unit.RETREAT_AT * 100) + "% hull a warship pulls back to the nearest Mender or base\n"
                + "(colony, Ship Station, Mothership), repairs to " + (int) (Unit.RETREAT_DONE * 100) + "%, then attack-moves back into the fight.\n"
                + "Any ship parked near your bases and out of combat for " + (int) UnitBehavior.REPAIR_DELAY + " s repairs "
                + (int) (UnitBehavior.REPAIR_RATE * 100) + "% of its hull per second.";
        return bx + bw + 8;
    }

    /** Turret / Shield upgrade buttons for the selected colony; the nearest free harvester goes and builds it. */
    private void colonyPanel(ShapeRenderer sr, World w, Faction p, Body b, float x0, float x1) {
        float top = BOTTOM - 12;
        int used = w.construction.pointsUsed(p, b);
        txt(x0, top, "Upgrade " + b.name + "  (" + used + "/" + Body.MAX_DEFENSE + " points used)", TITLE, 1f);
        Unit builder = w.construction.nearestFreeBuilder(p, b);
        float gap = 6, bh = 42, bw = Math.min(220, (x1 - x0 - gap) / 2);
        for (int i = 0; i < GameInput.PLANET_UPGRADES.length; i++) {
            ShipClass c = GameInput.PLANET_UPGRADES[i];
            float bx = x0 + i * (bw + gap), by = top - 28 - bh;
            String block = w.construction.baseBlocker(p, c);
            if (block == null && used >= Body.MAX_DEFENSE) block = b.name + " has used all " + Body.MAX_DEFENSE + " upgrade points";
            if (block == null && builder == null) block = "No free harvester to build it";
            String miss = p.missing(c.s.energy, c.s.metal, c.s.rare);
            boolean ok = block == null && miss == null;
            boolean hov = hovered(bx, by, bw, bh);
            if (ok) sr.setColor(hov ? 0.14f : 0.08f, hov ? 0.26f : 0.16f, hov ? 0.42f : 0.28f, 1f);
            else sr.setColor(hov ? 0.14f : 0.08f, hov ? 0.12f : 0.08f, hov ? 0.14f : 0.1f, 1f);
            sr.rect(bx, by, bw, bh);
            String label = "[" + GameInput.BUILD_KEY_NAMES[i] + "] " + c.s.name;
            txt(bx + 6, by + bh - 7, label, ok ? Color.WHITE : GRAY, fit(label, 0.85f, bw - 10));
            String cost = ProductionSystem.costText(c.s.energy, c.s.metal, c.s.rare) + "  " + (int) c.s.buildTime + "s";
            txt(bx + 6, by + 16, cost, ok ? new Color(0.85f, 0.85f, 0.6f, 1f) : new Color(0.65f, 0.45f, 0.45f, 1f), fit(cost, 0.72f, bw - 10));
            final ShipClass fc = c;
            buttons.add(new Btn(bx, by, bw, bh, () -> gs.input.upgradePlanet(b, fc)));
            if (hov) tooltip = shipTooltip(c, block != null ? block : miss);
        }
        wrap(x0, top - 28 - bh - 12, x1 - x0,
                "The nearest free Harvester flies over and builds the upgrade, then goes back to harvesting.  Each colony has "
                        + Body.MAX_DEFENSE + " upgrade points to split between turrets and shield."
                        + (builder != null ? "  Builder: Harvester " + (int) builder.dist(b.x, b.y) + " away." : ""),
                DIM, 0.78f);
    }

    private void infoPanel(ShapeRenderer sr, World w, Faction p, float x, float y, float width) {
        GameInput in = gs.input;
        List<Unit> sel = in.selected;
        if (sel.size() == 1) {
            unitInfo(sr, w, sel.get(0), x, y, width);
        } else if (sel.size() > 1) {
            txt(x, y, sel.size() + " units selected", TITLE, 1f);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Unit u : sel) counts.merge(u.s.name, 1, Integer::sum);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : counts.entrySet()) sb.append(e.getKey()).append(" x").append(e.getValue()).append("\n");
            wrap(x, y - 24, width, sb.toString(), Color.WHITE, 0.85f);
        } else if (in.inspectUnit != null) {
            unitInfo(sr, w, in.inspectUnit, x, y, width);
        } else if (in.inspectBody != null) {
            bodyInfo(w, in.inspectBody, x, y, width);
        } else {
            txt(x, y, "Nothing selected", DIM, 1f);
            wrap(x, y - 26, width,
                    "Harvest stars for energy and asteroids / barren planets for metal. Colonize habitable worlds for housing. "
                            + "Fortified worlds and Ancient Ruins must be conquered first. Build a Research Hub and send Surveyors "
                            + "to uninhabitable planets for rare items.", DIM, 0.8f);
        }
        destroyButton(sr, p, x, width);
    }

    /** "Destroy (Del)" for scuttling the selection for a 25% refund; "Surrender" when only the Mothership is selected. */
    private void destroyButton(ShapeRenderer sr, Faction p, float x, float width) {
        int n = 0, e = 0, m = 0;
        boolean ms = false;
        for (Unit u : gs.input.selected) {
            if (u.dead || u.owner != p) continue;
            if (spacerts.net.CommandExecutor.destroyable(u)) {
                int[] back = spacerts.net.CommandExecutor.scuttleRefund(u);
                e += back[0];
                m += back[1];
                n++;
            } else if (u.cls == ShipClass.MOTHERSHIP) ms = true;
        }
        if (n == 0 && !ms) return;
        boolean surrender = n == 0;
        String label = surrender ? "Surrender" : "Destroy (Del)  +25%";
        float bw = width(label, 0.85f) + 16, bh = 22, bx = x + width - bw, by = 8;
        boolean hov = hovered(bx, by, bw, bh);
        sr.setColor(hov ? 0.5f : 0.34f, hov ? 0.12f : 0.08f, hov ? 0.12f : 0.08f, 1f);
        sr.rect(bx, by, bw, bh);
        txt(bx + 8, by + 16, label, Color.WHITE, 0.85f);
        buttons.add(new Btn(bx, by, bw, bh, gs.input::destroySelected));
        if (hov) tooltip = surrender ? "Surrender: give up and lose the game (asks first)."
                : "Destroy " + n + (n == 1 ? " unit" : " units") + " for 25% of the price back: +" + e + " energy, +" + m + " metal.\n"
                + "Anything still queued in a destroyed station is refunded in full. No wreck is left behind.";
    }

    private void unitInfo(ShapeRenderer sr, World w, Unit u, float x, float y, float width) {
        String title = u.s.name + (u.cls == ShipClass.MOTHERSHIP || u.cls == ShipClass.FACTORY ? "  Tier " + ProductionSystem.roman(u.tier) : "")
                + (u.cls == ShipClass.PIRATE_BASE && u.tier >= 3 ? " - CITADEL" : "");
        txt(x, y, title, u.owner.color, 1.1f);
        if (u.owner != w.player) txt(x + width(title, 1.1f) + 10, y, "(" + u.owner.name + ")", DIM, 0.85f);
        float yy = y - 24;
        float pct = Math.max(0, u.hp / u.maxHp());
        sr.setColor(0.2f, 0.05f, 0.05f, 1f);
        sr.rect(x, yy - 14, width, 14);
        sr.setColor(1f - pct, 0.25f + pct * 0.7f, 0.25f, 1f);
        sr.rect(x, yy - 14, width * pct, 14);
        txt(x + 5, yy - 2, "HP " + (int) u.hp + " / " + (int) u.maxHp(), Color.BLACK, 0.75f);
        yy -= 19;
        if (u.maxShield() > 0) {
            float sp = u.shield / u.maxShield();
            sr.setColor(0.05f, 0.1f, 0.2f, 1f);
            sr.rect(x, yy - 13, width, 13);
            sr.setColor(0.4f, 0.75f, 1f, 1f);
            sr.rect(x, yy - 13, width * sp, 13);
            txt(x + 5, yy - 1, "Shield " + (int) u.shield + " / " + (int) u.maxShield(), Color.BLACK, 0.72f);
            yy -= 17;
        }
        yy -= 6;
        StringBuilder sb = new StringBuilder();
        if (u.owner == w.player) sb.append(status(w, u)).append("\n");
        if (u.armed()) sb.append(String.format("Dmg %.0f / %.1fs   Range %d", u.damage(), u.s.cooldown, (int) u.range())).append("\n");
        if (u.owner == w.player)
            for (Ability a : u.s.abilities) {
                float cd = u.abilityCds[a.ordinal()];
                sb.append(a.keyName).append(": ").append(a.title).append(cd > 0 ? " (" + (int) Math.ceil(cd) + "s)" : " - ready").append("\n");
            }
        if (u.ghostTimer > 0) sb.append("Ghost Drive: cloaked, can't fire (").append((int) Math.ceil(u.ghostTimer)).append("s)\n");
        switch (u.cls) {
            case COLONY:
                if (u.home != null) {
                    float inc = EconomySystem.colonyIncome(u.home);
                    sb.append(u.home.name).append(": +").append((int) u.home.housing(u.owner)).append(" housing\n");
                    sb.append(String.format("Produces +%.1f energy/s, +%.1f metal/s\n", inc, inc));
                    sb.append("Upgrades ").append(u.home.defensePoints()).append("/").append(Body.MAX_DEFENSE)
                            .append(": turrets ").append(u.home.turretPoints).append(", shield ").append(u.home.shieldPoints);
                    if (u.owner == w.player && u.home.defensePoints() < Body.MAX_DEFENSE) sb.append(" (keys 1 / 2)");
                    sb.append("\n");
                }
                break;
            case SOLAR:
                sb.append(String.format("Producing +%.1f energy/s", w.economy.solarRate(u)));
                if (u.home != null && !u.home.name.isEmpty()) sb.append(" from ").append(u.home.name);
                sb.append("\n");
                break;
            case MINER: {
                Body rock = u.body != null && !u.body.dead ? u.body : null;
                if (rock == null) sb.append(u.relocating ? "Relocating to the next asteroid field...\n" : "Depleted - no asteroids left in this system\n");
                else {
                    sb.append(String.format("Mining +%.1f metal/s", w.economy.minerRate(u)));
                    if (rock.kind == Body.Kind.ASTEROID) sb.append(" (asteroid: ").append((int) rock.metal).append(" left)");
                    else sb.append(" from ").append(rock.name);
                    sb.append("\n");
                }
                break;
            }
            case OUTPOST:
                if (u.home != null && u.home.type.rare != null) {
                    float interval = u.home.type.rare == RareItem.RELIC_FRAGMENT ? 90 : 45;
                    sb.append("Extracting ").append(u.home.type.rare.title).append(": ").append((int) (u.work / interval * 100)).append("%\n");
                }
                break;
            case HUB:
                sb.append("Hub tier ").append(u.owner.hubTier()).append("\n");
                if (u.owner == w.player && u.owner.researching != null) {
                    sb.append("Researching ").append(u.owner.researching.title).append(" ").append((int) (u.owner.researchProgress * 100)).append("%\n");
                    float bx = x, by = 12;
                    boolean hov = hovered(bx, by, 130, 22);
                    sr.setColor(hov ? 0.5f : 0.3f, hov ? 0.15f : 0.08f, hov ? 0.15f : 0.08f, 1f);
                    sr.rect(bx, by, 130, 22);
                    txt(bx + 8, by + 16, "Cancel research", Color.WHITE, 0.8f);
                    buttons.add(new Btn(bx, by, 130, 22, () -> gs.issue(new spacerts.net.Command(spacerts.net.Command.Type.CANCEL_RESEARCH, 0))));
                }
                break;
            default:
                break;
        }
        if (sb.length() < 60) sb.append(u.s.desc);
        wrap(x, yy, width, sb.toString(), Color.WHITE, 0.8f);
    }

    private String status(World w, Unit u) {
        if (u.warping) return "Warping";
        if (u.warpCharge > 0) return "Spooling warp drive...";
        switch (u.order) {
            case HARVEST:
                if (u.body == null) return "Harvesting";
                String what = u.body.kind == Body.Kind.STAR ? "energy" : "metal";
                return "Harvesting " + what + " (" + (int) u.cargo + "/" + (int) w.economy.capacity(u) + ")";
            case MOVE: return "Moving";
            case ATTACK_MOVE: return "Attack-moving";
            case ATTACK: return u.target != null ? "Attacking " + u.target.s.name : "Attacking";
            case COLONIZE: return "Colonizing " + (u.body != null ? u.body.name : "");
            case SURVEY: return "Surveying " + (u.body != null ? u.body.name : "");
            case BUILD:
                if (u.buildCls == null) return "Building";
                if (u.dist(u.buildX, u.buildY) > w.construction.workRange(u)) return "Flying to build " + u.buildCls.s.name;
                return "Building " + u.buildCls.s.name + " " + (int) (u.work * 100) + "%";
            default:
                if (u.queue != null && !u.queue.isEmpty()) return "Building " + u.queue.items.get(0).s.name;
                return u.target != null ? "Engaging " + u.target.s.name : "Idle";
        }
    }

    private void bodyInfo(World w, Body b, float x, float y, float width) {
        txt(x, y, b.kind == Body.Kind.PLANET ? b.name : b.title(), TITLE, 1.1f);
        StringBuilder sb = new StringBuilder();
        switch (b.kind) {
            case STAR:
                sb.append("Star - infinite ENERGY source.\nRight-click with harvesters selected to collect energy.");
                break;
            case ASTEROID:
                sb.append(b.giant ? "GIANT ASTEROID - METAL: " : "Asteroid - METAL: ").append(String.format("%,d", (long) b.metal)).append(" remaining.\nRight-click with harvesters to mine")
                        .append(b.giant ? ", or park an Asteroid Miner beside it: it will take days to drain." : ".");
                break;
            case GATE: {
                StarSystem to = b.link != null ? b.link.system : null;
                sb.append("Jump gate").append(to != null ? " to " + to.name + " (" + to.archetype.title + ")" : "").append(".\n")
                        .append("Right-click it with ships selected to jump through. Double-click it to look through it.");
                break;
            }
            case WRECK:
                sb.append("Wreckage - SALVAGE: ").append((int) b.metal).append(" metal.\nDrifts away in ").append((int) b.life)
                        .append("s. Right-click with harvesters or Mining Barges to salvage.");
                break;
            default:
                sb.append(b.type.title).append("\n");
                if (b.type.habitable) sb.append("Habitable: +").append((int) b.housing(w.player)).append(" housing when colonized.\n");
                if (b.type.minable) sb.append("Minable for metal (slow, infinite).\n");
                if (b.type.rare != null) sb.append("Rare item: ").append(b.type.rare.title).append(" (send a Surveyor)\n");
                if (b.guarded()) sb.append("DEFENDED by ").append(b.turretsAlive()).append(" native turrets + guards. Destroy the turrets first.\n");
                if (b.colonized()) sb.append("Colony of ").append(b.colony.owner.name)
                        .append(" - upgrades: turrets ").append(b.turretPoints).append(", shield ").append(b.shieldPoints)
                        .append(" (").append(b.defensePoints()).append("/").append(Body.MAX_DEFENSE).append(")\n");
                if (b.outpost != null && !b.outpost.dead) sb.append("Outpost of ").append(b.outpost.owner.name).append("\n");
                break;
        }
        wrap(x, y - 26, width, sb.toString(), Color.WHITE, 0.82f);
    }
    /** "Rally Stations ON/OFF (V)" button, right-aligned at x1 in the Mothership's panel header; returns its left edge. */
    private float rallyToggle(ShapeRenderer sr, Faction p, float x1, float y) {
        boolean on = p.rallyStations;
        String label = "Rally Stations " + (on ? "ON" : "OFF") + " (V)";
        float bw = width(label, 0.85f) + 14, bh = 20, bx = x1 - bw, by = y - 16;
        boolean hov = hovered(bx, by, bw, bh);
        if (on) sr.setColor(hov ? 0.15f : 0.1f, hov ? 0.4f : 0.3f, hov ? 0.22f : 0.16f, 1f);
        else sr.setColor(hov ? 0.25f : 0.16f, hov ? 0.25f : 0.18f, hov ? 0.35f : 0.26f, 1f);
        sr.rect(bx, by, bw, bh);
        txt(bx + 7, y, label, Color.WHITE, 0.85f);
        buttons.add(new Btn(bx, by, bw, bh, gs.input::toggleRally));
        if (hov) tooltip = "Rally Stations: your Ship Stations, Research Hub and Repair Stations line up in hex rings around\n"
                + "the Mothership and move as fast as it does (boosts and warp included). Off: they hold position and\n"
                + "crawl at half the Mothership's speed. Defenders always orbit outside the rings.";
        return bx;
    }

    private void buildPanel(ShapeRenderer sr, World w, Faction p, Unit prod, float x0, float x1) {
        float top = BOTTOM - 12;
        String title = "Build - " + prod.s.name + (prod.home != null ? " (" + prod.home.name + ")" : "");
        txt(x0, top, title, TITLE, 1f);

        float qLimit = x1;
        if (prod.cls == ShipClass.MOTHERSHIP) qLimit = rallyToggle(sr, p, x1, top) - 8;
        float qx = x0 + Math.max(260, width(title, 1f) + 30);
        txt(qx, top, "Queue:", DIM, 0.85f);
        qx += 52;
        for (int i = 0; i < prod.queue.items.size(); i++) {
            ShipClass c = prod.queue.items.get(i);
            float bx = qx + i * 74, by = top - 15, bw = 70, bh = 19;
            if (bx + bw > qLimit) break;
            boolean hov = hovered(bx, by, bw, bh);
            if (hov) sr.setColor(0.35f, 0.12f, 0.12f, 1f);
            else sr.setColor(0.08f, 0.14f, 0.24f, 1f);
            sr.rect(bx, by, bw, bh);
            if (i == 0) {
                sr.setColor(0.25f, 0.55f, 0.9f, 0.8f);
                sr.rect(bx, by, bw * prod.queue.progress, 3);
            }
            String nm = c.s.name.length() > 9 ? c.s.name.substring(0, 9) : c.s.name;
            txt(bx + 4, by + 14, nm, Color.WHITE, 0.72f);
            final int idx = i;
            buttons.add(new Btn(bx, by, bw, bh, () -> gs.issue(new spacerts.net.Command(spacerts.net.Command.Type.CANCEL_QUEUE, 0).target(prod).arg(idx))));
            if (hov) tooltip = c.s.name + "\nClick to cancel (full refund).";
        }

        ShipClass[] list = prod.cls.buildList();
        float bw = 156, bh = 42, gap = 6;
        int cols = Math.max(1, (int) ((x1 - x0 + gap) / (bw + gap)));
        for (int i = 0; i < list.length; i++) {
            ShipClass c = list[i];
            int col = i % cols, row = i / cols;
            float bx = x0 + col * (bw + gap);
            float by = top - 28 - (row + 1) * (bh + gap) + gap;
            if (by < 2) break;
            String block = w.production.blocker(prod, c);
            int ce = w.production.energyCost(p, c), cm = w.production.metalCost(p, c); // warships scale with copies owned
            String miss = p.missing(ce, cm, c.s.rare);
            boolean locked = c.s.tech != null && !p.has(c.s.tech);
            boolean ok = block == null && miss == null;
            boolean hov = hovered(bx, by, bw, bh);
            if (ok) sr.setColor(hov ? 0.14f : 0.08f, hov ? 0.26f : 0.16f, hov ? 0.42f : 0.28f, 1f);
            else sr.setColor(hov ? 0.14f : 0.08f, hov ? 0.12f : 0.08f, hov ? 0.14f : 0.1f, 1f);
            sr.rect(bx, by, bw, bh);
            if (c.s.hasRare() || c == ShipClass.LEVIATHAN) {
                sr.setColor(1f, 0.8f, 0.3f, ok ? 0.8f : 0.3f);
                sr.rect(bx, by + bh - 2, bw, 2);
            }
            String key = i < GameInput.BUILD_KEY_NAMES.length ? "[" + GameInput.BUILD_KEY_NAMES[i] + "] " : "";
            String label = key + c.s.name + (c.s.squad > 1 ? " x" + c.s.squad : "") + (locked ? "  (locked)" : "")
                    + (c == ShipClass.STELLAR_REFINEMENT ? "  Lv " + p.refinement : "");
            txt(bx + 6, by + bh - 7, label, ok ? Color.WHITE : GRAY, fit(label, 0.85f, bw - 10));
            String cost = ProductionSystem.costText(ce, cm, c.s.rare);
            int pop = c.s.pop * c.s.squad;
            if (pop > 0) cost += "  " + pop + "H";
            boolean pricier = ce > c.s.energy || cm > c.s.metal;
            txt(bx + 6, by + 16, cost, ok ? (pricier ? new Color(1f, 0.75f, 0.45f, 1f) : new Color(0.85f, 0.85f, 0.6f, 1f)) : new Color(0.65f, 0.45f, 0.45f, 1f), fit(cost, 0.72f, bw - 10));
            final ShipClass fc = c;
            buttons.add(new Btn(bx, by, bw, bh, () -> gs.input.enqueue(prod, fc, false)));
            if (hov) {
                tooltip = shipTooltip(c, block != null ? block : miss);
                if (c == ShipClass.STELLAR_REFINEMENT) {
                    tooltip += "\n\nLevel " + p.refinement + " / " + spacerts.world.Faction.REFINEMENT_MAX + ": mining and energy +"
                            + Math.round(spacerts.world.Faction.REFINEMENT_STEP * 100 * p.refinement) + "% now, +"
                            + Math.round(spacerts.world.Faction.REFINEMENT_STEP * 100 * (p.refinement + 1)) + "% after the next level.\n"
                            + "Next level costs " + ProductionSystem.costText(ce, cm, c.s.rare)
                            + "  (base 2000E / 2000M x (1 + ln(1 + level)): level 10 = 3.4x, 100 = 5.6x, 999 = 7.9x).";
                } else if (pricier) {
                    int n = w.production.copies(p, c);
                    tooltip += "\n\nPrice now " + ProductionSystem.costText(ce, cm, c.s.rare) + "  (base " + c.s.energy + "E / " + c.s.metal + "M, +"
                            + Math.round((w.production.priceMult(p, c) - 1f) * 100) + "% for " + n + " already in service).\n"
                            + "Warships cost base x (1 + " + ProductionSystem.PRICE_K + " x ln(1 + copies)): variety is cheaper.";
                }
            }
        }
    }

    /** Harvester construction list: Factory, Hub, Planet Defense, Mender. */
    private void constructPanel(ShapeRenderer sr, World w, Faction p, Unit h, float x0, float x1) {
        float top = BOTTOM - 12;
        String title = "Construct - Harvester";
        txt(x0, top, title, TITLE, 1f);
        String state;
        if (gs.input.placing != null) state = "Placing " + gs.input.placing.s.name + " - left-click to place, right-click / Esc to cancel";
        else if (h.buildCls != null) state = "Building " + h.buildCls.s.name + " " + (int) (h.work * 100) + "%  (X stops and refunds)";
        else state = "Cost is paid when ordered and refunded if the harvester stops or dies.";
        txt(x0 + width(title, 1f) + 24, top, state, DIM, 0.8f);

        ShipClass[] list = h.cls.buildList();
        float gap = 6, bh = 42;
        float bw = Math.min(190, (x1 - x0 - gap * (list.length - 1)) / list.length);
        for (int i = 0; i < list.length; i++) {
            ShipClass c = list[i];
            float bx = x0 + i * (bw + gap);
            float by = top - 28 - bh;
            if (bx + bw > x1) break;
            String block = w.construction.baseBlocker(p, c);
            String miss = p.missing(c.s.energy, c.s.metal, c.s.rare);
            boolean ok = block == null && miss == null;
            boolean hov = hovered(bx, by, bw, bh);
            boolean active = gs.input.placing == c;
            if (active) sr.setColor(0.18f, 0.4f, 0.25f, 1f);
            else if (ok) sr.setColor(hov ? 0.14f : 0.08f, hov ? 0.26f : 0.16f, hov ? 0.42f : 0.28f, 1f);
            else sr.setColor(hov ? 0.14f : 0.08f, hov ? 0.12f : 0.08f, hov ? 0.14f : 0.1f, 1f);
            sr.rect(bx, by, bw, bh);
            String key = "[" + GameInput.BUILD_KEY_NAMES[i] + "] ";
            String label = key + c.s.name;
            txt(bx + 6, by + bh - 7, label, ok ? Color.WHITE : GRAY, fit(label, 0.85f, bw - 10));
            String cost = ProductionSystem.costText(c.s.energy, c.s.metal, c.s.rare);
            if (c.s.pop > 0) cost += "  " + c.s.pop + "H";
            cost += "  " + (int) c.s.buildTime + "s";
            txt(bx + 6, by + 16, cost, ok ? new Color(0.85f, 0.85f, 0.6f, 1f) : new Color(0.65f, 0.45f, 0.45f, 1f), fit(cost, 0.72f, bw - 10));
            final ShipClass fc = c;
            buttons.add(new Btn(bx, by, bw, bh, () -> gs.input.startBuild(fc)));
            if (hov) tooltip = shipTooltip(c, block != null ? block : miss);
        }
        wrap(x0, top - 28 - bh - 12, x1 - x0,
                "Station / Hub / Repair Station: click open space.  "
                        + "Solar Collector: click a star.  Asteroid Miner: click an asteroid or metal planet.  "
                        + "Hold Shift to place several with more selected harvesters.  Planet upgrades: click the colony itself.",
                DIM, 0.78f);
    }

    private String shipTooltip(ShipClass c, String problem) {
        Spec s = c.s;
        StringBuilder sb = new StringBuilder();
        if (c.isPlanetUpgrade()) {
            sb.append(s.name).append("\n").append(s.desc).append("\n");
            if (c == ShipClass.DEF_TURRET)
                sb.append("Each turret: ").append((int) (ShipClass.TURRET.s.hp * Unit.DURABILITY)).append(" HP, range ").append((int) ShipClass.TURRET.s.range).append(".\n");
            else
                sb.append("Colony shield +").append((int) (Body.SHIELD_PER_POINT * Unit.DURABILITY * Unit.COLONY_SHIELD_MULT)).append(", recharge +")
                        .append((int) (Body.REGEN_PER_POINT * Unit.DURABILITY * Unit.SHIELD_REGEN_MULT * Unit.COLONY_SHIELD_MULT)).append("/s per point.\n");
            sb.append("A colony has ").append(Body.MAX_DEFENSE).append(" upgrade points in total - split them between turrets and shield.\n")
                    .append("Build ").append((int) s.buildTime).append("s   Cost: ").append(ProductionSystem.costText(s.energy, s.metal, s.rare));
            if (problem != null) sb.append("\n\n> ").append(problem);
            return sb.toString();
        }
        sb.append(s.name).append(s.squad > 1 ? " (squad of " + s.squad + ")" : "").append("\n").append(s.desc).append("\n\n");
        sb.append("HP ").append((int) (s.hp * Unit.DURABILITY));
        if (s.shield > 0) sb.append("   Shield ").append((int) (s.shield * Unit.DURABILITY));
        if (s.armor > 0) sb.append("   Armor ").append((int) (s.armor * 100)).append("%");
        sb.append("\n");
        if (s.damage > 0) {
            sb.append(String.format("Weapon %.0f dmg / %.2fs, range %d", s.damage, s.cooldown, (int) s.range));
            if (s.multi > 1) sb.append(", ").append(s.multi).append(" targets");
            if (s.splash > 0) sb.append(", splash");
            if (s.piercing) sb.append(", armor-piercing");
            sb.append("\n");
            if (s.bonusVs != null) sb.append("x").append(s.bonusMult).append(" damage vs ").append(s.bonusVs.name().toLowerCase()).append("\n");
        }
        if (c == ShipClass.SOLAR) sb.append("Produces ").append((int) spacerts.systems.ConstructionSystem.SOLAR_RATE)
                .append(" energy/s (a Harvester averages about 2-3/s). Max ").append(spacerts.systems.ConstructionSystem.SOLAR_PER_STAR).append(" per star.\n");
        if (c == ShipClass.MINER) sb.append("Mines ").append((int) spacerts.systems.ConstructionSystem.MINER_RATE)
                .append(" metal/s from asteroids within ").append((int) spacerts.systems.ConstructionSystem.MINER_REACH)
                .append(" (").append((int) spacerts.systems.ConstructionSystem.MINER_PLANET_RATE).append("/s forever on a metal planet). One per field.\n");
        if (s.healRate > 0) sb.append("Repairs every ally within ").append((int) s.healRange).append(" by ").append((int) (s.healRate * Unit.DURABILITY)).append(" HP/s\n");
        if (s.speed > 0) sb.append("Speed ").append((int) s.speed).append("   ");
        sb.append("Vision ").append((int) s.vision).append("   Build ").append((int) s.buildTime).append("s\n");
        sb.append("Cost: ").append(ProductionSystem.costText(s.energy, s.metal, s.rare));
        if (s.pop > 0) sb.append(", ").append(s.pop * s.squad).append(" housing");
        if (problem != null) sb.append("\n\n> ").append(problem);
        return sb.toString();
    }

    private void researchPanel(ShapeRenderer sr, World w, Faction p) {
        Tech[] all = Tech.values();
        float bw = 204, bh = 38, gap = 5;
        int cols = Math.max(1, (int) ((W - 24 + gap) / (bw + gap)));
        int rows = (all.length + cols - 1) / cols;
        rpW = cols * (bw + gap) - gap + 12;
        rpH = rows * (bh + gap) + 34;
        rpX = 10;
        rpY = BOTTOM + 8;
        rpVisible = true;
        sr.setColor(PANEL);
        sr.rect(rpX, rpY, rpW, rpH);
        border(sr, rpX, rpY, rpW, rpH, ACCENT);
        String head = "RESEARCH HUB - Tier " + p.hubTier();
        if (p.researching != null)
            head += "      Researching: " + p.researching.title + " (" + (int) (p.researchProgress * 100) + "%) - click it again to cancel";
        else head += "      Select a technology to research";
        txt(rpX + 8, rpY + rpH - 9, head, TITLE, 0.9f);

        for (int i = 0; i < all.length; i++) {
            Tech t = all[i];
            int col = i % cols, row = i / cols;
            float bx = rpX + 6 + col * (bw + gap);
            float by = rpY + rpH - 30 - (row + 1) * (bh + gap) + gap;
            boolean done = p.has(t);
            boolean active = p.researching == t;
            String block = w.production.researchBlocker(p, t);
            String miss = p.missing(t.energy, t.metal, t.rare);
            boolean ok = block == null && miss == null;
            boolean hov = hovered(bx, by, bw, bh);
            if (done) sr.setColor(0.06f, 0.2f, 0.1f, 1f);
            else if (active) sr.setColor(0.08f, 0.2f, 0.35f, 1f);
            else if (ok) sr.setColor(hov ? 0.14f : 0.09f, hov ? 0.22f : 0.15f, hov ? 0.36f : 0.25f, 1f);
            else sr.setColor(hov ? 0.12f : 0.07f, hov ? 0.12f : 0.07f, hov ? 0.14f : 0.09f, 1f);
            sr.rect(bx, by, bw, bh);
            sr.setColor(t.branch.color.r, t.branch.color.g, t.branch.color.b, done || ok || active ? 0.9f : 0.35f);
            sr.rect(bx, by, 3, bh);
            if (active) {
                sr.setColor(0.3f, 0.65f, 1f, 0.9f);
                sr.rect(bx, by, bw * p.researchProgress, 3);
            }
            Color tc = done ? new Color(0.5f, 0.9f, 0.55f, 1f) : (ok || active ? Color.WHITE : GRAY);
            txt(bx + 8, by + bh - 6, t.title, tc, fit(t.title, 0.78f, bw - 12));
            String sub = done ? "Researched" : "T" + t.tier + "  " + ProductionSystem.costText(t.energy, t.metal, t.rare);
            txt(bx + 8, by + 15, sub, done ? DIM : (ok ? new Color(0.85f, 0.85f, 0.6f, 1f) : new Color(0.6f, 0.45f, 0.45f, 1f)), fit(sub, 0.68f, bw - 12));
            final Tech ft = t;
            buttons.add(new Btn(bx, by, bw, bh, () -> { // clicking the active research cancels it
                if (!p.has(ft)) gs.issue(new spacerts.net.Command(spacerts.net.Command.Type.RESEARCH, 0).tech(ft));
            }));
            if (hov) {
                StringBuilder sb = new StringBuilder();
                sb.append(t.title).append("  [").append(t.branch.title).append("]\n").append(t.desc).append("\n\n");
                sb.append("Cost: ").append(ProductionSystem.costText(t.energy, t.metal, t.rare)).append("\n");
                sb.append("Time: ").append((int) t.time).append("s   Requires Hub tier ").append(t.tier);
                if (t.prereq() != null) sb.append("\nPrerequisite: ").append(t.prereq().title);
                if (!done && !active && (block != null || miss != null)) sb.append("\n\n> ").append(block != null ? block : miss);
                tooltip = sb.toString();
            }
        }
    }

    // ------------------------------------------------------------------ galaxy map (Tab, Hive only)

    private float gmOx, gmOy, gmScale;

    private void galaxyFrame(World w) {
        float areaW = W - 40, areaH = H - TOP - BOTTOM - 30;
        gmScale = Math.min(areaW / w.galaxy.width, areaH / w.galaxy.height);
        gmOx = (W - w.galaxy.width * gmScale) / 2;
        gmOy = BOTTOM + 15 + (areaH - w.galaxy.height * gmScale) / 2;
    }

    /** True once the player has seen any part of the system. */
    private static boolean explored(World w, StarSystem s) {
        VisionGrid vg = w.player.vision;
        if (vg.isExplored(s.cx, s.cy)) return true;
        for (int k = 0; k < 6; k++)
            if (vg.isExplored(s.cx + (s.cornerX(k) - s.cx) * 0.55f, s.cy + (s.cornerY(k) - s.cy) * 0.55f)) return true;
        for (Body g : s.gates) if (g != null && vg.isExplored(g.x, g.y)) return true;
        return false;
    }

    /** The system under a screen point on the galaxy map, or null. */
    public StarSystem galaxyPick(float sx, float syDown) {
        World w = gs.world;
        if (!w.galaxy.hive) return null;
        galaxyFrame(w);
        float y = Gdx.graphics.getHeight() - syDown;
        float wx = (sx - gmOx) / gmScale, wy = (y - gmOy) / gmScale;
        StarSystem s = w.systemAt(wx, wy);
        return s.dist(wx, wy) < s.radius * 1.1f ? s : null;
    }

    private static Color dangerColor(int d) {
        float t = Math.min(1f, d / 10f);
        return new Color(0.5f + 0.5f * t, 0.9f - 0.6f * t, 0.5f - 0.3f * t, 0.9f);
    }

    private static String controlText(StarSystem s) {
        switch (s.control) {
            case OWNED: return "yours";
            case CONTESTED: return "yours - CONTESTED";
            case PIRATE: return "pirate stronghold";
            default: return "unclaimed";
        }
    }

    private void galaxyMap(ShapeRenderer sr, World w) {
        galaxyFrame(w);
        int n = w.galaxy.systems.size();
        int[] mine = new int[n], foes = new int[n], cols = new int[n];
        boolean[] base = new boolean[n];
        for (Unit u : w.units) {
            if (u.dead || u.cls == ShipClass.DRONE) continue;
            int id = w.systemAt(u.x, u.y).id;
            if (u.owner == w.player) {
                if (u.cls == ShipClass.COLONY) cols[id]++;
                else if (!u.s.internal) mine[id]++;
            } else if (u.cls == ShipClass.PIRATE_BASE) base[id] = true;
            else if (u.owner == w.pirates && w.canSee(w.player, u)) foes[id]++;
        }
        StarSystem here = w.systemAt(gs.cam.position.x, gs.cam.position.y);
        StarSystem alert = w.time - w.alertTime < 10 ? w.systemAt(w.alertX, w.alertY) : null;
        StarSystem hover = galaxyPick(mx, H - my);
        sr.setColor(0, 0, 0.02f, 0.35f);
        sr.rect(0, BOTTOM, W, H - TOP - BOTTOM);
        float rr = Galaxy.HEX_R * gmScale;
        for (StarSystem s : w.galaxy.systems) {
            float cx = gmOx + s.cx * gmScale, cy = gmOy + s.cy * gmScale;
            boolean seen = explored(w, s);
            // links to neighbours (gate lanes)
            if (seen) for (int k = 0; k < 6; k++) {
                StarSystem nb = s.neighbours[k];
                if (nb == null || nb.id < s.id) continue;
                sr.setColor(0.35f, 0.7f, 1f, 0.18f);
                sr.rectLine(cx, cy, gmOx + nb.cx * gmScale, gmOy + nb.cy * gmScale, 1.5f);
            }
            // fill
            float fr, fg, fb, fa;
            // conquest colours: grey = nobody's, blue = yours, orange pulse = contested, red = pirate stronghold
            boolean flash = ((int) (w.time * 3)) % 2 == 0;
            if (s.control == StarSystem.Control.CONTESTED) { fr = 1f; fg = 0.6f; fb = 0.2f; fa = flash ? 0.45f : 0.25f; }
            else if (s.owned()) { fr = w.player.color.r; fg = w.player.color.g; fb = w.player.color.b; fa = 0.42f; }
            else if (!seen) { fr = 0.1f; fg = 0.12f; fb = 0.18f; fa = 0.25f; }
            else if (base[s.id]) { fr = 1f; fg = 0.25f; fb = 0.2f; fa = 0.35f; }
            else if (cols[s.id] > 0) { fr = w.player.color.r; fg = w.player.color.g; fb = w.player.color.b; fa = 0.22f; }
            else { fr = 0.45f; fg = 0.47f; fb = 0.52f; fa = 0.16f; } // grey: unclaimed
            if (s == hover) fa += 0.12f;
            sr.setColor(fr, fg, fb, fa);
            for (int k = 0; k < 6; k++) {
                double a0 = Math.PI / 3 * k, a1 = Math.PI / 3 * (k + 1);
                sr.triangle(cx, cy, cx + (float) Math.cos(a0) * rr, cy + (float) Math.sin(a0) * rr,
                        cx + (float) Math.cos(a1) * rr, cy + (float) Math.sin(a1) * rr);
            }
            // outline
            boolean pulse = s == alert && ((int) (w.time * 4)) % 2 == 0;
            if (pulse) sr.setColor(World.ALERT);
            else if (s == here) sr.setColor(0.4f, 0.9f, 1f, 1f);
            else sr.setColor(0.4f, 0.55f, 0.8f, seen ? 0.55f : 0.25f);
            float lw = s == here || pulse ? 2.5f : 1f;
            for (int k = 0; k < 6; k++) {
                double a0 = Math.PI / 3 * k, a1 = Math.PI / 3 * (k + 1);
                sr.rectLine(cx + (float) Math.cos(a0) * rr, cy + (float) Math.sin(a0) * rr,
                        cx + (float) Math.cos(a1) * rr, cy + (float) Math.sin(a1) * rr, lw);
            }
            if (!seen) continue;
            float ts = Math.max(0.6f, Math.min(1f, rr / 40f));
            txt(cx - rr * 0.6f, cy + rr * 0.45f, s.archetype.icon, s.archetype.color, ts);
            if (mine[s.id] > 0) txt(cx - rr * 0.6f, cy - rr * 0.05f, "" + mine[s.id], w.player.color, ts * 0.85f);
            if (foes[s.id] > 0) txt(cx + rr * 0.1f, cy - rr * 0.05f, "" + foes[s.id], World.ALERT, ts * 0.85f);
            if (base[s.id]) txt(cx + rr * 0.05f, cy + rr * 0.45f, s.archetype == spacerts.world.SystemArchetype.CITADEL ? "C" : "P", new Color(1f, 0.35f, 0.3f, 1f), ts);
            txt(cx - rr * 0.15f, cy - rr * 0.42f, "" + s.danger, dangerColor(s.danger), ts * 0.75f); // danger level
        }
        txt(20, H - TOP - 14, "GALAXY MAP  -  click a system to view it, right-click to send the selected ships there.  Tab / Esc closes.", TITLE, 0.9f);
        txt(20, H - TOP - 34, "Blue: your colonies   Red: pirate strongholds   Numbers: your ships / pirates seen   "
                + "* Nursery  o Belt  G Garden  F Fortress  R Frontier  A Ruins  ~ Nebula  . Void  P Haven", DIM, 0.75f);
        if (hover != null) {
            boolean seenH = explored(w, hover);
            String info = hover.name + " - " + (seenH ? hover.archetype.title : "unexplored")
                    + "   (" + w.galaxy.jumps(here, hover) + " jumps from view)\n"
                    + "Danger " + hover.danger + " / " + w.galaxy.maxDanger() + "   Control: " + controlText(hover);
            if (seenH) {
                int patrols = spacerts.world.PirateBases.patrolsIn(w, hover);
                info += "\n" + (patrols > 0 ? patrols + " pirate patrol ships sighted" : "No pirate patrols sighted");
            }
            tooltip = info;
        }
        txt(20, H - TOP - 54, "Conquest: build a Claim Beacon (Harvester) in a hex to own it. Numbers at the bottom of each hex are its danger.", DIM, 0.75f);
    }

    /** World area the minimap shows: the system the camera is in (the whole map in classic games). */
    private float miniOx, miniOy, miniScale;

    private void miniFrame(World w) {
        StarSystem cs = w.systemAt(gs.cam.position.x, gs.cam.position.y);
        float span = Math.max(cs.maxX() - cs.minX(), cs.maxY() - cs.minY());
        miniOx = cs.cx - span / 2;
        miniOy = cs.cy - span / 2;
        miniScale = MINI / span;
    }

    private void minimap(ShapeRenderer sr, World w) {
        miniFrame(w);
        StarSystem sys = w.systemAt(gs.cam.position.x, gs.cam.position.y);
        float x0 = W - MINI - 8, y0 = 7, s = miniScale;
        float ox = x0 - miniOx * s, oy = y0 - miniOy * s; // screen position of world (0, 0)
        sr.setColor(ACCENT);
        sr.rect(x0 - 2, y0 - 2, MINI + 4, MINI + 4);
        sr.setColor(0, 0, 0.01f, 1f);
        sr.rect(x0, y0, MINI, MINI);
        VisionGrid vg = w.player.vision;
        float cs = vg.cell * s;
        int c0 = Math.max(0, (int) (sys.minX() / vg.cell)), c1 = Math.min(vg.cols - 1, (int) (sys.maxX() / vg.cell));
        int r0 = Math.max(0, (int) (sys.minY() / vg.cell)), r1 = Math.min(vg.rows - 1, (int) (sys.maxY() / vg.cell));
        for (int cy = r0; cy <= r1; cy++)
            for (int cx = c0; cx <= c1; cx++) {
                int i = cy * vg.cols + cx;
                if (!vg.explored[i]) continue;
                if (!sys.contains((cx + 0.5f) * vg.cell, (cy + 0.5f) * vg.cell)) continue;
                if (vg.visible[i]) sr.setColor(0.07f, 0.11f, 0.2f, 1f);
                else sr.setColor(0.035f, 0.05f, 0.1f, 1f);
                sr.rect(ox + cx * vg.cell * s, oy + cy * vg.cell * s, cs + 0.3f, cs + 0.3f);
            }
        if (sys.hex) { // hex outline and its gates
            sr.setColor(0.25f, 0.45f, 0.7f, 0.8f);
            for (int k = 0; k < 6; k++)
                sr.rectLine(ox + sys.cornerX(k) * s, oy + sys.cornerY(k) * s, ox + sys.cornerX(k + 1) * s, oy + sys.cornerY(k + 1) * s, 1f);
        }
        for (Body b : sys.bodies) {
            if (b.dead || !vg.isExplored(b.x, b.y)) continue;
            float bx = ox + b.x * s, by = oy + b.y * s;
            switch (b.kind) {
                case GATE:
                    sr.setColor(0.45f, 0.85f, 1f, 1f);
                    sr.rect(bx - 2.5f, by - 2.5f, 5, 5);
                    break;
                case STAR:
                    sr.setColor(b.color);
                    sr.rect(bx - 1.5f, by - 1.5f, 3, 3);
                    break;
                case ASTEROID:
                case WRECK:
                    sr.setColor(0.45f, 0.43f, 0.4f, 1f);
                    sr.rect(bx - 0.5f, by - 0.5f, 1, 1);
                    break;
                default:
                    if (b.colonized()) sr.setColor(b.colony.owner.color);
                    else if (b.guarded()) sr.setColor(1f, 0.3f, 0.25f, 1f);
                    else sr.setColor(b.color);
                    sr.rect(bx - 1.5f, by - 1.5f, 3, 3);
                    break;
            }
        }
        for (Unit u : w.units) {
            if (u.dead || u.cls == ShipClass.DRONE || u.cls == ShipClass.COLONY || !w.canSee(w.player, u)) continue;
            if (u.owner == w.natives || !sys.contains(u.x, u.y)) continue;
            if (u.cls == ShipClass.PIRATE_BASE) sr.setColor(1f, 0.3f, 0.25f, 1f);
            else sr.setColor(u.owner.color);
            float sz = u.cls == ShipClass.MOTHERSHIP || u.cls == ShipClass.PIRATE_BASE ? 5 : (u.cls.isBuilding() ? 2.5f : 1.6f);
            sr.rect(ox + u.x * s - sz / 2, oy + u.y * s - sz / 2, sz, sz);
        }
        Faction pl = w.player;
        for (spacerts.world.Ping pg : w.pings) {
            if (!pg.sniper || pg.owner != pl || !sys.contains(pg.x, pg.y)) continue;
            sr.setColor(1f, 0.25f, 0.2f, ((int) (w.time * 6)) % 2 == 0 ? 1f : 0.4f);
            sr.rect(ox + pg.x * s - 2.5f, oy + pg.y * s - 2.5f, 5, 5);
        }
        if (pl.hasFlag && sys.contains(pl.flagX, pl.flagY)) {
            float fx = ox + pl.flagX * s, fy = oy + pl.flagY * s;
            sr.setColor(Color.WHITE);
            sr.rect(fx - 0.5f, fy, 1, 8);
            sr.setColor(pl.color);
            sr.triangle(fx + 0.5f, fy + 8, fx + 0.5f, fy + 4, fx + 6, fy + 6);
        }
        OrthographicCamera cam = gs.cam;
        float vw = cam.viewportWidth * cam.zoom * s, vh = cam.viewportHeight * cam.zoom * s;
        float cx = ox + cam.position.x * s - vw / 2, cy = oy + cam.position.y * s - vh / 2;
        float rx0 = Math.max(x0, cx), ry0 = Math.max(y0, cy), rx1 = Math.min(x0 + MINI, cx + vw), ry1 = Math.min(y0 + MINI, cy + vh);
        if (rx1 > rx0 && ry1 > ry0) border(sr, rx0, ry0, rx1 - rx0, ry1 - ry0, new Color(1f, 1f, 1f, 0.7f));
        if (w.time - w.alertTime < 8 && ((int) (w.time * 4)) % 2 == 0 && sys.contains(w.alertX, w.alertY)) {
            float ax = ox + w.alertX * s, ay = oy + w.alertY * s;
            border(sr, ax - 6, ay - 6, 12, 12, World.ALERT);
        }
    }

    private void messages(World w) {
        float y = gs.showGalaxy && w.galaxy.hive ? H - TOP - 74 : H - TOP - 12; // below the galaxy map header
        // multiplayer lists the empires at the top right: keep messages clear of it
        float maxW = gs.world.setup.mode == spacerts.world.GameSetup.Mode.MULTIPLAYER ? W - 460 : W * 0.6f;
        int shown = 0;
        for (int i = w.messages.size() - 1; i >= 0 && shown < 6; i--) {
            Message m = w.messages.get(i);
            float age = w.time - m.time;
            if (age > m.life() || age < 0) continue;
            float a = age > m.life() - 2 ? (m.life() - age) / 2f : 1f;
            Txt t = wrap(14, y, maxW, m.text, m.color, 0.95f); // wrapped so it never runs into the right-hand overlays
            t.c.a = a;
            y -= wrappedHeight(m.text, 0.95f, maxW) + 8;
            shown++;
        }
    }

    private void overlayBox(ShapeRenderer sr, float bw, float bh) {
        sr.setColor(0, 0, 0, 0.55f);
        sr.rect(0, 0, W, H);
        float x = (W - bw) / 2, y = (H - bh) / 2;
        sr.setColor(0.03f, 0.06f, 0.13f, 0.97f);
        sr.rect(x, y, bw, bh);
        border(sr, x, y, bw, bh, ACCENT);
    }

    private static final Color PAGE_C = new Color(1f, 0.85f, 0.45f, 1f);

    /** Tutorial page currently shown (the F1 overlay is paginated). */
    private int helpPage;

    /** Turns the tutorial page; turning past the last page closes it. */
    public void helpPage(int delta) {
        int n = helpPages().length;
        int next = helpPage + delta;
        if (next >= n) {
            gs.showHelp = false;
            helpPage = 0;
            return;
        }
        helpPage = Math.max(0, next);
    }

    /** Tutorial pages: {title, body}. */
    private String[][] helpPages() {
        List<String[]> pages = new ArrayList<>();
        pages.add(new String[]{"Welcome",
                "GOAL: Survive and expand forever. Rival empires and ever-growing pirate raids will come for you. Lose your Mothership and it is over.\n\n"
                        + "THE BASICS\n"
                        + "  Left-click or drag to select. Right-click to move, attack, harvest, colonize or survey. Ctrl+Right-click: attack-move.\n"
                        + "  WASD, the arrow keys, the screen edges or a middle-drag pan the camera; the mouse wheel zooms; click the minimap to jump.\n"
                        + "  Space pauses, [ and ] change the game speed, F5 / F9 save and load, Esc opens the menu, F1 shows this tutorial again.\n\n"
                        + "Use the arrow keys, A / D, or the buttons below to turn the pages."});
        pages.add(new String[]{"Economy",
                "  Harvesters (built by the Mothership) collect ENERGY from stars and METAL from asteroids, barren planets and WRECKS.\n"
                        + "  Mining Barges (Ship Station) are tough, slow haulers with a 150 hold. Destroyed ships leave wrecks worth HALF their metal.\n"
                        + "  Scarce finds turn up now and then: Dark Matter (mining asteroids), Star Essence (starlight), Neutronium (salvage), Exotic Matter (ruins).\n\n"
                        + "  HOUSING limits your fleet: colonize Habitable Worlds or conquer Fortified Worlds. Every new colony brings a free Harvester.\n"
                        + "  Ships fly 2x faster toward your colonies, and 1.75x toward any planet or while charging into an attack.\n\n"
                        + "  UPKEEP: a big army drains energy every second (top bar). Ships count their housing, each station counts 4.\n"
                        + "  Up to 40: free.  41-100: low.  Over 100: high.\n"
                        + "  REPAIRS cost metal: a full repair costs about half a ship's metal price. With no metal, repairs stop."});
        pages.add(new String[]{"Building and stations",
                "  Select a Harvester, then press a number key:\n"
                        + "  1 Ship Station   2 Research Hub   3 Repair Station (heals everything within 400)   4 Solar Collector   5 Asteroid Miner\n"
                        + "  6 Command Center (tactics).   Mothership tier II: 8 Defense Platform   9 Ore Refinery (+25% metal)   0 Sensor Array.   Tier III: - Ion Cannon.\n\n"
                        + "  PLANET UPGRADES: click your colony, then 1 Turret Upgrade or 2 Shield Upgrade. The nearest Harvester builds it (5 points per colony).\n\n"
                        + "  STATIONS (Ship Stations, the Research Hub, Repair Stations) can move, at half the Mothership's speed.\n"
                        + "  V (or the button on the Mothership's panel) RALLIES them: they line up in hex rings around the Mothership and move as fast as it does.\n\n"
                        + "  TIERS (select it, then U or its upgrade button): Mothership I-III adds hull, guns and buildings. Ship Station I-III builds\n"
                        + "  30% faster per tier; tier II unlocks Corsair, Tempest, Aegis and Phantom; tier III unlocks Sovereign, Halcyon and Leviathan.\n"
                        + "  WARSHIP PRICES grow with how many of that type you have: 10 in service = +84%, 50 = +138%. Mix your fleet."});
        pages.add(new String[]{"Warships and skills",
                "  Lancer beats Longbow, Longbow beats Bulwark, Bulwark beats Lancer.\n"
                        + "  Wisp scout - Lancer infantry - Bulwark tank - Longbow sniper - Hive carrier - Leviathan capital ship.\n"
                        + "  DEFENDER (Mothership tier II, up to 4): a huge escort that orbits the Mothership and never leaves its post.\n\n"
                        + "ACTIVATED SKILLS - each has its own key (or click its button in the bottom panel):\n"
                        + "  Q  Sensor Ping (Wisp): reveals a wide area, cloaked ships included.\n"
                        + "  E  Ghost Drive (Wisp): cloaked and 60% faster for 6 s, but it cannot fire.\n"
                        + "  T  Taunt (Bulwark, Aegis, Defender): nearby enemies must shoot it.\n"
                        + "  Z  Evasive Dash (Longbow, Phantom): leaps 400 away from the nearest threat.\n"
                        + "  N  Nova Cannon (Leviathan): huge area blast (needs research).\n"
                        + "  O  Overcharge (Sovereign): double rate of fire for 6 s.\n\n"
                        + "  Snipers are revealed for 4 s with a red crosshair whenever they shoot at you.\n"
                        + "  Brood Swarm research: a destroyed Hive releases 5 Broodlings that keep fighting."});
        pages.add(new String[]{"Research and automation",
                "  Select the Research Hub (B) to research tech and build Surveyors.\n"
                        + "  Surveyors land on uninhabitable planets and extract RARE ITEMS: Volcanic = Pyro, Ice = Cryo, Gas giant = Plasma,\n"
                        + "  Crystal = Void, Ancient Ruins (guarded) = Relic. Rare items unlock upgrades and rare ships (Phantom, Aegis, Leviathan).\n\n"
                        + "  G toggles AUTO-DEFENSE: idle warships defend your bases and harvesters flee raids.\n"
                        + "  R toggles AUTO-RETREAT for the selected warships: below 25% hull they pull back to a Repair Station or base, repair, and return.\n"
                        + "  F places the RALLY FLAG where new warships gather (Shift+F removes). Shift+right-click with a station sets its own rally point."});
        if (gs.world.galaxy.hive)
            pages.add(new String[]{"The Hive galaxy",
                    "  You start at the BOTTOM. Danger rises toward the Pirate Citadel at the top.\n"
                            + "  7 Claim Beacon (Harvester) claims a hex: more income, and no stronghold rises near it.\n"
                            + "  JUMP GATES join the hexes: right-click a gate to fly through. Tab: GALAXY MAP (grey = unclaimed, blue = yours, red = pirate).\n"
                            + "  Destroy the Citadel, claim its hex and build the ASCENSION GATE (= key, Mothership tier III) to win. J jumps to the last alert."});
        pages.add(new String[]{"Command Center and tactics",
                "  Build a COMMAND CENTER (Harvester, key 6, one per empire). Its build list unlocks TACTICS and tactic upgrades;\n"
                        + "  select it to pick the ACTIVE TACTIC. Then press Y (or the Tactic button above the bottom bar) to turn tactics ON.\n"
                        + "  While ON, an attack order (Ctrl+right-click, or right-click an enemy) first FORMS the selected ships up, then they\n"
                        + "  MARCH together at the slowest ship's speed and fight with the tactic. Ships the tactic has no place for just attack-move.\n\n"
                        + "  BULLDOZER: infantry ring the tanks, support behind, snipers at the back; snipers shoot whatever is closest to the tanks.\n"
                        + "  PINCER: tanks in front, infantry wings on the sides; once the tanks are hurt the wings surround the enemy.\n"
                        + "     Snipers switch between the nearest and the farthest target every 3 shots.\n"
                        + "  FLANK: tanks + half the fleet in front, the rest behind a gap. Hurt tanks taunt (again on every recharge) while the\n"
                        + "     rear half swings round both sides to strike 900 past the tanks.\n"
                        + "  FOCUS: infantry, then tanks, then snipers; snipers kill hive drones first, then the biggest hull, +50% vs tanks and Hives.\n\n"
                        + "  UPGRADES: Martyr Charge (Flank), Crippling Line (Pincer), Ramming Plates and Shield Wall (Bulldozer), Replenish (all).\n"
                        + "  TARGETING (K, or the button in the bottom panel): Nearest, Farthest, Most health, Least health or Random."});
        pages.add(new String[]{"Hotkeys",
                "  H Mothership   F2 cycle Ship Stations   B Research Hub   I idle harvesters   Ctrl+A all warships\n"
                        + "  1-9, 0, -, = build from the selected station / structure / harvester   U tier upgrade\n"
                        + "  Q E T Z N O skills   Y tactics on/off   K targeting   R auto-retreat   G auto-defense   V rally stations   F / Shift+F rally flag\n"
                        + "  X stop   C center on selection   J jump to the last alert   Tab galaxy map (Hive)\n"
                        + "  Space pause   [ ] speed   F5 save   F9 load   Esc menu   F1 tutorial\n\n"
                        + "Good luck, Commander. Press Enter or click Start to begin."});
        return pages.toArray(new String[0][]);
    }

    private void help(ShapeRenderer sr) {
        String[][] pages = helpPages();
        helpPage = Math.max(0, Math.min(helpPage, pages.length - 1));
        float bw = Math.min(W - 40, 960), bh = Math.min(H - 40, 560);
        overlayBox(sr, bw, bh);
        float bx0 = (W - bw) / 2, by0 = (H - bh) / 2;
        float x = bx0 + 24, y = by0 + bh - 22;
        txt(x, y, "NEBULA DOMINION - How to play", TITLE, 1.4f);
        aligned(bx0, y, bw - 24, "Page " + (helpPage + 1) + " / " + pages.length, DIM, 0.9f, Align.right);
        txt(x, y - 36, pages[helpPage][0].toUpperCase(), PAGE_C, 1.15f);
        wrap(x, y - 66, bw - 48, pages[helpPage][1], Color.WHITE, 0.9f);

        // page dots
        float dots = pages.length * 16, dx = (W - dots) / 2 + 8;
        for (int i = 0; i < pages.length; i++) {
            if (i == helpPage) sr.setColor(ACCENT);
            else sr.setColor(0.25f, 0.32f, 0.45f, 1f);
            sr.circle(dx + i * 16, by0 + 62, i == helpPage ? 5 : 4, 12);
        }

        // Back / Next (Start on the last page) / Skip
        float btnW = 130, btnH = 34, btnY = by0 + 14;
        boolean last = helpPage == pages.length - 1;
        helpButton(sr, bx0 + 20, btnY, btnW, btnH, "< Back", helpPage > 0, () -> helpPage(-1));
        helpButton(sr, bx0 + bw - btnW - 20, btnY, btnW, btnH, last ? "Start" : "Next >", true, () -> helpPage(+1));
        helpButton(sr, (W - btnW) / 2, btnY, btnW, btnH, "Skip tutorial", !last, () -> {
            gs.showHelp = false;
            helpPage = 0;
        });
    }

    private void helpButton(ShapeRenderer sr, float x, float y, float w, float h, String label, boolean enabled, Runnable action) {
        boolean hov = enabled && hovered(x, y, w, h);
        if (!enabled) sr.setColor(0.06f, 0.08f, 0.12f, 1f);
        else sr.setColor(hov ? 0.2f : 0.12f, hov ? 0.32f : 0.2f, hov ? 0.55f : 0.36f, 1f);
        sr.rect(x, y, w, h);
        border(sr, x, y, w, h, enabled ? ACCENT : DIM);
        aligned(x, y + h / 2 + 7, w, label, enabled ? Color.WHITE : DIM, 1f, Align.center);
        if (enabled) buttons.add(new Btn(x, y, w, h, action));
    }

    /** "Are you sure?" before surrendering. */
    private void surrenderConfirm(ShapeRenderer sr) {
        float bw = 460, bh = 190;
        overlayBox(sr, bw, bh);
        float x = (W - bw) / 2, y = (H + bh) / 2;
        aligned(x, y - 26, bw, "SURRENDER?", World.ALERT, 1.5f, Align.center);
        aligned(x + 20, y - 70, bw - 40, "Your empire will be eliminated and the game is lost.\nThis cannot be undone. Are you sure?", Color.WHITE, 0.95f, Align.center);
        float b = 180, gap = 20, by = (H - bh) / 2 + 20, bx = (W - 2 * b - gap) / 2;
        helpButton(sr, bx, by, b, 36, "Yes, surrender", true, gs.input::surrender);
        helpButton(sr, bx + b + gap, by, b, 36, "Cancel (Esc)", true, () -> gs.confirmSurrender = false);
    }

    /** Chat input box above the bottom panel, with the recent chat lines above it. */
    private void chatBox(ShapeRenderer sr, World w) {
        GameInput in = gs.input;
        float bw = Math.min(900, W * 0.6f), bh = 26, bx = 12, by = BOTTOM + 14;
        // recent chat, newest at the bottom
        // the last 30 chat lines; 8 are shown, Page Up / Page Down scroll back through the rest
        List<spacerts.world.Message> all = new ArrayList<>();
        for (int i = w.messages.size() - 1; i >= 0 && all.size() < 30; i--)
            if (w.messages.get(i).chat) all.add(0, w.messages.get(i));
        in.chatScroll = Math.max(0, Math.min(in.chatScroll, all.size() - 8));
        int end = all.size() - in.chatScroll;
        List<spacerts.world.Message> lines = all.subList(Math.max(0, end - 8), Math.max(0, end));
        if (all.size() > 8)
            txt(bx + bw + 10, by + 40, (in.chatScroll > 0 ? "scrolled back " + in.chatScroll + "  " : "") + "PgUp / PgDn history", DIM, 0.75f);
        if (!lines.isEmpty()) {
            float hh = lines.size() * 18 + 8;
            sr.setColor(0.02f, 0.04f, 0.09f, 0.75f);
            sr.rect(bx, by + bh + 4, bw, hh);
            float ly = by + bh + 4 + hh - 6;
            for (spacerts.world.Message m : lines) {
                txt(bx + 8, ly, m.text, m.color, fit(m.text, 0.85f, bw - 16));
                ly -= 18;
            }
        }
        sr.setColor(0.03f, 0.06f, 0.13f, 0.95f);
        sr.rect(bx, by, bw, bh);
        border(sr, bx, by, bw, bh, in.chatTeam ? World.GOOD : ACCENT);
        String prompt = in.chatTeam ? "Say (team): " : "Say (all): ";
        float pw = width(prompt, 0.9f);
        txt(bx + 8, by + 18, prompt, in.chatTeam ? World.GOOD : TITLE, 0.9f);
        String text = in.chatText.toString();
        float room = bw - 20 - pw;
        while (text.length() > 0 && width(text, 0.9f) > room) text = text.substring(1); // keep the end in view
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        txt(bx + 8 + pw, by + 18, text + (blink ? "_" : ""), Color.WHITE, 0.9f);
        txt(bx + bw + 10, by + 18, "Enter send   Esc cancel   /t team", DIM, 0.75f);
    }

    private void pauseMenu(ShapeRenderer sr) {
        float bw = 380, bh = 260;
        overlayBox(sr, bw, bh);
        float x = (W - bw) / 2, y = (H + bh) / 2;
        aligned(x, y - 24, bw, "MENU", TITLE, 1.5f, Align.center);
        String[] labels = {"Resume (Esc)", "Save game (F5)", "Load game (F9)", "Main menu (M)"};
        Runnable[] actions = {() -> gs.menuOpen = false, gs::save, gs::load, gs::toMenu};
        for (int i = 0; i < labels.length; i++) {
            float bx = x + 60, by = y - 90 - i * 42, w = bw - 120, h = 32;
            boolean hov = hovered(bx, by, w, h);
            sr.setColor(hov ? 0.15f : 0.08f, hov ? 0.28f : 0.16f, hov ? 0.45f : 0.28f, 1f);
            sr.rect(bx, by, w, h);
            aligned(bx, by + 22, w, labels[i], Color.WHITE, 1f, Align.center);
            buttons.add(new Btn(bx, by, w, h, actions[i]));
        }
    }

    /** Shown once when the Ascension Gate completes; "Keep playing" closes it. */
    private boolean victoryDismissed;

    private void victory(ShapeRenderer sr, World w) {
        float bw = 620, bh = 230;
        overlayBox(sr, bw, bh);
        float x = (W - bw) / 2, y = (H + bh) / 2;
        aligned(x, y - 30, bw, "ASCENSION", World.GOOD, 1.8f, Align.center);
        aligned(x, y - 80, bw, "The Ascension Gate is complete. The pirate clans are broken.", Color.WHITE, 1.05f, Align.center);
        aligned(x, y - 108, bw, "Conquered " + spacerts.systems.ConquestSystem.ownedCount(w) + " of " + w.galaxy.systems.size()
                + " hexes in " + clock(w.time) + ".", DIM, 0.95f, Align.center);
        float bx = x + bw / 2 - 110, by = y - bh + 26, bW = 220, bH = 34;
        boolean hov = hovered(bx, by, bW, bH);
        sr.setColor(hov ? 0.15f : 0.08f, hov ? 0.4f : 0.28f, hov ? 0.25f : 0.18f, 1f);
        sr.rect(bx, by, bW, bH);
        aligned(bx, by + 23, bW, "Keep playing", Color.WHITE, 1f, Align.center);
        buttons.add(new Btn(bx, by, bW, bH, () -> victoryDismissed = true));
    }

    public boolean victoryShowing() {
        return gs.world.ascended && !victoryDismissed;
    }

    private void gameOver(ShapeRenderer sr, World w) {
        float bw = 520, bh = 200;
        overlayBox(sr, bw, bh);
        float x = (W - bw) / 2, y = (H + bh) / 2;
        aligned(x, y - 30, bw, "MOTHERSHIP DESTROYED", World.ALERT, 1.6f, Align.center);
        aligned(x, y - 80, bw, "You survived " + clock(w.time) + ".", Color.WHITE, 1.1f, Align.center);
        aligned(x, y - 140, bw, "Press Enter to return to the main menu", DIM, 1f, Align.center);
    }

    private void drawTooltip(ShapeRenderer sr) {
        BitmapFont f = gs.game.font;
        float scale = 0.82f;
        f.getData().setScale(scale);
        layout.setText(f, tooltip, Color.WHITE, 360, Align.left, true);
        float tw = layout.width + 18, th = layout.height + 18;
        f.getData().setScale(1f);
        float x = Math.min(mx + 16, W - tw - 4);
        float y = my + 24 + th > H ? my - 16 - th : my + 24;
        y = Math.max(4, y);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(0.02f, 0.03f, 0.07f, 0.97f);
        sr.rect(x, y, tw, th);
        border(sr, x, y, tw, th, ACCENT);
        sr.end();
        wrap(x + 9, y + th - 9, 360, tooltip, Color.WHITE, scale);
        flushTexts();
    }
}

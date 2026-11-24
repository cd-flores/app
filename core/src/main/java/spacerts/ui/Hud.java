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
import spacerts.units.Ability;
import spacerts.units.ShipClass;
import spacerts.units.Spec;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.Message;
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
        if (gs.showHelp) {
            gs.showHelp = false;
            return true;
        }
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
        float wx = (sx - x0) / MINI * World.SIZE, wy = (y - y0) / MINI * World.SIZE;
        return new float[]{Math.max(0, Math.min(World.SIZE, wx)), Math.max(0, Math.min(World.SIZE, wy))};
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
        topBar(sr, w, p);
        bottomPanel(sr, w, p);
        minimap(sr, w);
        messages(w);
        sr.end();
        flushTexts();

        if (gs.showHelp || gs.menuOpen || w.gameOver) {
            buttons.clear();
            sr.begin(ShapeRenderer.ShapeType.Filled);
            if (gs.showHelp) help(sr);
            else if (w.gameOver) gameOver(sr, w);
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
        float y = H - 9, x = 12;
        x = stat(x, y, "Energy " + (int) p.energy, ENERGY_C);
        x = stat(x, y, "Metal " + (int) p.metal, METAL_C);
        x = stat(x, y, "Housing " + p.popUsed + "/" + p.popCap, p.popUsed >= p.popCap ? World.ALERT : Color.WHITE);
        x += 12;
        for (RareItem r : RareItem.values()) x = stat(x, y, r.shortName + " " + p.rare[r.ordinal()], r.color);

        // auto-defense toggle (also G)
        String ad = "Auto-defend " + (p.autoDefense ? "ON" : "OFF") + " (G)";
        float bw = width(ad, 0.85f) + 14, bx = x, by = H - TOP + 3, bh = TOP - 6;
        boolean hov = hovered(bx, by, bw, bh);
        if (p.autoDefense) sr.setColor(hov ? 0.15f : 0.1f, hov ? 0.4f : 0.3f, hov ? 0.22f : 0.16f, 1f);
        else sr.setColor(hov ? 0.4f : 0.3f, hov ? 0.15f : 0.1f, hov ? 0.15f : 0.1f, 1f);
        sr.rect(bx, by, bw, bh);
        txt(bx + 7, y, ad, Color.WHITE, 0.85f);
        buttons.add(new Btn(bx, by, bw, bh, () -> p.autoDefense = !p.autoDefense));
        if (hov) tooltip = "Auto-defense: idle warships attack enemies near your bases (about 1.5x their strength),\n"
                + "harvesters flee from raids, badly damaged ships retreat to a Mender, and your ships focus fire.\n"
                + "Ships you are giving orders to are never overridden.";

        float toRaid = Math.max(0, w.threats.nextWave - w.time);
        String right = w.difficulty.title + "     Time " + clock(w.time) + "     Pirate raid ~" + clock(toRaid) + "     Speed x" + gs.speed
                + (gs.paused ? "     PAUSED" : "") + "     F Rally flag" + (p.hasFlag ? " (set)" : "") + "     F1 Help";
        aligned(W - 712, y, 700, right, toRaid < 45 ? World.ALERT : DIM, 1f, Align.right);
    }

    private float stat(float x, float y, String s, Color c) {
        txt(x, y, s, c, 1f);
        return x + width(s, 1f) + 22;
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
        } else if (builder != null) {
            constructPanel(sr, w, p, builder, x0, x1);
        } else {
            float y = BOTTOM - 14;
            txt(x0, y, "Commands", TITLE, 1f);
            String hint = "Left-click / drag: select     Right-click: move, attack, harvest, colonize, survey     Ctrl+Right-click: attack-move\n"
                    + "H: Mothership (harvesters)   F2: Ship Factories (warships)   B: Research Hub   I: idle harvesters   Ctrl+A: all warships\n"
                    + "F: place rally flag (Shift+F removes)   Q: ability   X: stop   C: center on selection   Double-click: select all of a type\n"
                    + "Select a Harvester to build Factories, the Hub, planet defenses and Menders.   Space: pause   [ ]: speed   F5 / F9: save / load";
            wrap(x0, y - 26, x1 - x0, hint, DIM, 0.85f);
        }
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
    }

    private void unitInfo(ShapeRenderer sr, World w, Unit u, float x, float y, float width) {
        txt(x, y, u.s.name, u.owner.color, 1.1f);
        if (u.owner != w.player) txt(x + width(u.s.name, 1.1f) + 10, y, "(" + u.owner.name + ")", DIM, 0.85f);
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
        if (u.s.ability != Ability.NONE && u.owner == w.player)
            sb.append("Q: ").append(u.s.ability.title).append(u.abilityCd > 0 ? " (" + (int) Math.ceil(u.abilityCd) + "s)" : " - ready").append("\n");
        switch (u.cls) {
            case COLONY:
                if (u.home != null) {
                    float inc = EconomySystem.colonyIncome(u.home);
                    sb.append(u.home.name).append(": +").append((int) u.home.housing(u.owner)).append(" housing\n");
                    sb.append(String.format("Produces +%.1f energy/s, +%.1f metal/s\n", inc, inc));
                    sb.append("Upgrades ").append(u.home.defensePoints()).append("/").append(Body.MAX_DEFENSE)
                            .append(": turrets ").append(u.home.turretPoints).append(", shield ").append(u.home.shieldPoints);
                    if (u.owner == w.player && u.home.defensePoints() < Body.MAX_DEFENSE) sb.append(" (build with a Harvester)");
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
                if (rock == null) sb.append("Depleted - no asteroids left within reach\n");
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
                    final Faction f = u.owner;
                    buttons.add(new Btn(bx, by, 130, 22, () -> w.production.cancelResearch(f)));
                }
                break;
            default:
                break;
        }
        if (sb.length() < 60) sb.append(u.s.desc);
        wrap(x, yy, width, sb.toString(), Color.WHITE, 0.8f);
    }

    private String status(World w, Unit u) {
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
                sb.append("Asteroid - METAL: ").append((int) b.metal).append(" remaining.\nRight-click with harvesters to mine.");
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
    private void buildPanel(ShapeRenderer sr, World w, Faction p, Unit prod, float x0, float x1) {
        float top = BOTTOM - 12;
        String title = "Build - " + prod.s.name + (prod.home != null ? " (" + prod.home.name + ")" : "");
        txt(x0, top, title, TITLE, 1f);

        float qx = x0 + Math.max(260, width(title, 1f) + 30);
        txt(qx, top, "Queue:", DIM, 0.85f);
        qx += 52;
        for (int i = 0; i < prod.queue.items.size(); i++) {
            ShipClass c = prod.queue.items.get(i);
            float bx = qx + i * 74, by = top - 15, bw = 70, bh = 19;
            if (bx + bw > x1) break;
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
            buttons.add(new Btn(bx, by, bw, bh, () -> w.production.cancel(prod, idx)));
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
            String miss = p.missing(c.s.energy, c.s.metal, c.s.rare);
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
            txt(bx + 6, by + bh - 7, key + c.s.name + (c.s.squad > 1 ? " x" + c.s.squad : "") + (locked ? "  (locked)" : ""),
                    ok ? Color.WHITE : GRAY, 0.85f);
            String cost = ProductionSystem.costText(c.s.energy, c.s.metal, c.s.rare);
            int pop = c.s.pop * c.s.squad;
            if (pop > 0) cost += "  " + pop + "H";
            txt(bx + 6, by + 16, cost, ok ? new Color(0.85f, 0.85f, 0.6f, 1f) : new Color(0.65f, 0.45f, 0.45f, 1f), fit(cost, 0.72f, bw - 10));
            final ShipClass fc = c;
            buttons.add(new Btn(bx, by, bw, bh, () -> {
                String err = w.production.enqueue(prod, fc);
                if (err != null) w.notify(p, err, World.ALERT);
            }));
            if (hov) tooltip = shipTooltip(c, block != null ? block : miss);
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
                "Factory / Hub: click open space.  Turret / Shield Upgrade: click one of your colonies - each colony has "
                        + Body.MAX_DEFENSE + " upgrade points to split between turrets and shield.  Mender: built where the harvester is.  "
                        + "Solar Collector: click a star.  Asteroid Miner: click an asteroid or metal planet.  "
                        + "Hold Shift to place several with more selected harvesters.",
                DIM, 0.78f);
    }

    private String shipTooltip(ShipClass c, String problem) {
        Spec s = c.s;
        StringBuilder sb = new StringBuilder();
        if (c.isPlanetUpgrade()) {
            sb.append(s.name).append("\n").append(s.desc).append("\n");
            if (c == ShipClass.DEF_TURRET)
                sb.append("Each turret: ").append((int) ShipClass.TURRET.s.hp).append(" HP, range ").append((int) ShipClass.TURRET.s.range).append(".\n");
            else
                sb.append("Colony shield +").append((int) Body.SHIELD_PER_POINT).append(", recharge +").append((int) Body.REGEN_PER_POINT).append("/s per point.\n");
            sb.append("A colony has ").append(Body.MAX_DEFENSE).append(" upgrade points in total - split them between turrets and shield.\n")
                    .append("Build ").append((int) s.buildTime).append("s   Cost: ").append(ProductionSystem.costText(s.energy, s.metal, s.rare));
            if (problem != null) sb.append("\n\n> ").append(problem);
            return sb.toString();
        }
        sb.append(s.name).append(s.squad > 1 ? " (squad of " + s.squad + ")" : "").append("\n").append(s.desc).append("\n\n");
        sb.append("HP ").append((int) s.hp);
        if (s.shield > 0) sb.append("   Shield ").append((int) s.shield);
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
        if (s.healRate > 0) sb.append("Repairs every ally within ").append((int) s.healRange).append(" by ").append((int) s.healRate).append(" HP/s\n");
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
            buttons.add(new Btn(bx, by, bw, bh, () -> {
                if (p.researching == ft) {
                    w.production.cancelResearch(p);
                    return;
                }
                if (p.has(ft)) return;
                String err = w.production.startResearch(p, ft);
                if (err != null) w.notify(p, err, World.ALERT);
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

    private void minimap(ShapeRenderer sr, World w) {
        float x0 = W - MINI - 8, y0 = 7, s = MINI / World.SIZE;
        sr.setColor(ACCENT);
        sr.rect(x0 - 2, y0 - 2, MINI + 4, MINI + 4);
        sr.setColor(0, 0, 0.01f, 1f);
        sr.rect(x0, y0, MINI, MINI);
        VisionGrid vg = w.player.vision;
        float cs = vg.cell * s;
        for (int cy = 0; cy < vg.cols; cy++)
            for (int cx = 0; cx < vg.cols; cx++) {
                int i = cy * vg.cols + cx;
                if (!vg.explored[i]) continue;
                if (vg.visible[i]) sr.setColor(0.07f, 0.11f, 0.2f, 1f);
                else sr.setColor(0.035f, 0.05f, 0.1f, 1f);
                sr.rect(x0 + cx * cs, y0 + cy * cs, cs + 0.3f, cs + 0.3f);
            }
        for (Body b : w.bodies) {
            if (b.dead || !vg.isExplored(b.x, b.y)) continue;
            float bx = x0 + b.x * s, by = y0 + b.y * s;
            switch (b.kind) {
                case STAR:
                    sr.setColor(b.color);
                    sr.rect(bx - 1.5f, by - 1.5f, 3, 3);
                    break;
                case ASTEROID:
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
            if (u.owner == w.natives) continue;
            sr.setColor(u.owner.color);
            float sz = u.cls == ShipClass.MOTHERSHIP ? 4 : (u.cls.isBuilding() ? 2.5f : 1.6f);
            sr.rect(x0 + u.x * s - sz / 2, y0 + u.y * s - sz / 2, sz, sz);
        }
        Faction pl = w.player;
        for (spacerts.world.Ping pg : w.pings) {
            if (!pg.sniper || pg.owner != pl) continue;
            sr.setColor(1f, 0.25f, 0.2f, ((int) (w.time * 6)) % 2 == 0 ? 1f : 0.4f);
            sr.rect(x0 + pg.x * s - 2.5f, y0 + pg.y * s - 2.5f, 5, 5);
        }
        if (pl.hasFlag) {
            float fx = x0 + pl.flagX * s, fy = y0 + pl.flagY * s;
            sr.setColor(Color.WHITE);
            sr.rect(fx - 0.5f, fy, 1, 8);
            sr.setColor(pl.color);
            sr.triangle(fx + 0.5f, fy + 8, fx + 0.5f, fy + 4, fx + 6, fy + 6);
        }
        OrthographicCamera cam = gs.cam;
        float vw = cam.viewportWidth * cam.zoom * s, vh = cam.viewportHeight * cam.zoom * s;
        float cx = x0 + cam.position.x * s - vw / 2, cy = y0 + cam.position.y * s - vh / 2;
        float rx0 = Math.max(x0, cx), ry0 = Math.max(y0, cy), rx1 = Math.min(x0 + MINI, cx + vw), ry1 = Math.min(y0 + MINI, cy + vh);
        if (rx1 > rx0 && ry1 > ry0) border(sr, rx0, ry0, rx1 - rx0, ry1 - ry0, new Color(1f, 1f, 1f, 0.7f));
        if (w.time - w.alertTime < 8 && ((int) (w.time * 4)) % 2 == 0) {
            float ax = x0 + w.alertX * s, ay = y0 + w.alertY * s;
            border(sr, ax - 6, ay - 6, 12, 12, World.ALERT);
        }
    }

    private void messages(World w) {
        float y = H - TOP - 12;
        int shown = 0;
        for (int i = w.messages.size() - 1; i >= 0 && shown < 6; i--) {
            Message m = w.messages.get(i);
            float age = w.time - m.time;
            if (age > 12 || age < 0) continue;
            float a = age > 10 ? (12 - age) / 2f : 1f;
            Txt t = txt(14, y, m.text, m.color, 0.95f);
            t.c.a = a;
            y -= 20;
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

    private void help(ShapeRenderer sr) {
        float bw = Math.min(W - 40, 960), bh = Math.min(H - 40, 720);
        overlayBox(sr, bw, bh);
        float x = (W - bw) / 2 + 24, y = (H + bh) / 2 - 22;
        txt(x, y, "NEBULA DOMINION - How to play", TITLE, 1.4f);
        String body =
                "GOAL: Survive and expand forever. Rival empires and ever-growing pirate raids will come for you. Lose your Mothership and it is over.\n\n"
                        + "ECONOMY\n"
                        + "  Harvesters (built by the Mothership) collect ENERGY from stars and METAL from asteroids and barren planets.\n"
                        + "  Colonies also trickle in energy and metal. HOUSING limits your fleet: colonize Habitable Worlds (+10) or conquer Fortified Worlds (+25).\n"
                        + "  Ships fly 50% faster when heading to one of your colonies.\n\n"
                        + "BUILDING (select a Harvester)\n"
                        + "  1 Ship Factory - builds all warships and Colony Ships.  2 Research Hub.  On your colonies: 3 Turret Upgrade (+2 turrets)\n"
                        + "  or 4 Shield Upgrade (+400 planetary shield) - each colony has 5 upgrade points to split however you like.\n"
                        + "  5 Mender - a crawling mobile repair station (6 housing) that heals everything around it.\n"
                        + "  6 Solar Collector (next to a star, 6 energy/s)  7 Asteroid Miner (asteroid field or metal planet, 5 metal/s) - no hauling.\n\n"
                        + "RESEARCH\n"
                        + "  Select the Research Hub (B) to research tech and build Surveyors.\n"
                        + "  Surveyors land on uninhabitable planets and extract RARE ITEMS: Volcanic=Pyro, Ice=Cryo, Gas giant=Plasma,\n"
                        + "  Crystal=Void, Ancient Ruins (guarded)=Relic. Rare items unlock upgrades and rare ships (Phantom, Aegis, Leviathan).\n\n"
                        + "WARSHIPS  (Lancer beats Longbow, Longbow beats Bulwark, Bulwark beats Lancer)\n"
                        + "  Wisp scout - Lancer infantry - Bulwark tank - Longbow sniper - Mender support - Hive carrier - Leviathan capital ship\n\n"
                        + "CONTROLS\n"
                        + "  Left-click / drag to select. Right-click to move / attack / harvest / colonize / survey. Ctrl+Right-click: attack-move.\n"
                        + "  F places the RALLY FLAG where new warships gather (Shift+F removes). Shift+right-click with a factory sets its own rally point; right-click moves it (factories and the Hub crawl like the Mothership).\n"
                        + "  G toggles AUTO-DEFENSE: idle warships defend your bases, harvesters flee raids, damaged ships retreat to Menders.\n"
                        + "  Snipers (Longbow, Phantom) are revealed for 4 s with a red crosshair whenever they shoot at you.\n"
                        + "  Number keys 1-9, 0, -, = build from the selected factory / structure / harvester.\n"
                        + "  H Mothership   F2 Ship Factories   B Research Hub   I idle harvesters   Ctrl+A all warships   Q ability   X stop   C center\n"
                        + "  WASD / arrows / screen edges / middle-drag to pan, mouse wheel to zoom, click the minimap to jump.\n"
                        + "  Space pause   [ ] game speed   F5 save   F9 load   Esc menu   F1 this help\n\n"
                        + "Press F1, Esc, Enter or click to start.";
        wrap(x, y - 40, bw - 48, body, Color.WHITE, 0.9f);
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
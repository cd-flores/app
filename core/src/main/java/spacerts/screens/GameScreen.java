package spacerts.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.ScreenUtils;
import spacerts.SpaceRtsGame;
import spacerts.input.GameInput;
import spacerts.net.Command;
import spacerts.net.CommandExecutor;
import spacerts.net.Lockstep;
import spacerts.save.SaveManager;
import spacerts.ui.Hud;
import spacerts.ui.WorldRenderer;
import spacerts.units.Unit;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

public class GameScreen extends ScreenAdapter implements Lockstep.Runner {
    public final SpaceRtsGame game;
    public World world;
    public final OrthographicCamera cam = new OrthographicCamera();
    public final OrthographicCamera hudCam = new OrthographicCamera();
    public final GameInput input;
    public final WorldRenderer renderer;
    public final Hud hud;
    public boolean paused, showHelp = true, menuOpen;
    /** "Are you sure?" popup before surrendering. */
    public boolean confirmSurrender;
    /** Tactic toggle (Y / the button above the bottom bar): attack orders use the active tactic. */
    public boolean tacticsOn;
    /** Hive galaxy map overlay (Tab). */
    public boolean showGalaxy;
    public int speed = 1;
    private static final float STEP = 1f / 30f;
    private static final float AUTOSAVE_EVERY = 300f;
    private float nextAutosave = -1;
    /** LAN games: the lockstep link (null for Campaign and offline games against bots). */
    public final Lockstep net;
    /** Offline: commands issued since the last frame, applied before the next tick. */
    private final List<Command> localCommands = new ArrayList<>();
    private boolean desyncShown;

    // ---- LAN loading screen: the game is drawn (warming up) behind it until every machine is ready
    /** Frames drawn behind the loading screen before this machine reports ready. */
    public static final int LOAD_FRAMES = 90;
    /** Seconds of game time the throwaway warm-up world runs. */
    public static final float WARMUP_SECONDS = 20f;
    private volatile boolean simWarm;
    private int loadFrames;
    private float loadTime;
    private com.badlogic.gdx.graphics.glutils.ShapeRenderer loadSr;

    public GameScreen(SpaceRtsGame game, World world) {
        this(game, world, null);
    }

    public GameScreen(SpaceRtsGame game, World world, Lockstep net) {
        this.game = game;
        this.world = world;
        this.net = net;
        cam.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        hudCam.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.zoom = 1.4f;
        input = new GameInput(this);
        renderer = new WorldRenderer(this);
        hud = new Hud(this);
        centerOnMothership();
        nextAutosave = world.time + AUTOSAVE_EVERY;
        if (net != null) {
            showHelp = false; // the help overlay can't pause a shared game
            Thread warm = new Thread(() -> { // compiles the hot simulation code on a throwaway copy; the real world is untouched
                try {
                    warmUp(world.setup);
                } catch (Throwable ignored) {
                } finally {
                    simWarm = true;
                }
            }, "sim-warmup");
            warm.setDaemon(true);
            warm.start();
        }
    }

    /** Builds a scratch world from the same setup and runs it for a while, then throws it away. */
    public static void warmUp(spacerts.world.GameSetup setup) {
        World scratch = new World(setup);
        spacerts.world.GalaxyGenerator.generate(scratch);
        scratch.flushPending();
        int ticks = (int) (WARMUP_SECONDS / Lockstep.STEP);
        for (int i = 0; i < ticks; i++) scratch.update(Lockstep.STEP);
    }

    public boolean networked() {
        return net != null;
    }

    /** Saving and loading only make sense when this machine owns the whole game. */
    public boolean canSave() {
        return net == null;
    }

    public void centerOnMothership() {
        Unit ms = world.player.mothership;
        if (ms != null && !ms.dead) {
            cam.position.set(ms.x, ms.y, 0);
            cam.update();
        }
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(input);
    }

    /** Sends a player action to the simulation (through the LAN in multiplayer, next tick offline). */
    public void issue(Command c) {
        c.faction = world.player.id;
        if (net != null) net.submit(c);
        else localCommands.add(c);
    }

    public void togglePause() {
        if (net != null) issue(new Command(Command.Type.PAUSE, 0).arg(paused ? 0 : 1));
        else paused = !paused;
    }

    public void changeSpeed(int delta) {
        int s = Math.max(1, Math.min(4, speed + delta));
        if (s == speed) return;
        if (net != null) issue(new Command(Command.Type.SPEED, 0).arg(s));
        else speed = s;
    }

    @Override
    public void render(float delta) {
        if (net != null && !net.go) {
            renderLoading(delta);
            return;
        }
        input.update(delta);
        if (net != null) {
            net.update(delta, this);
            if (net.desyncTurn >= 0 && !desyncShown) {
                desyncShown = true;
                world.announce("Desync detected at turn " + net.desyncTurn + " - the machines no longer agree. Restart the game.", World.ALERT);
            }
            if (net.hostLost && !hostLostShown) {
                hostLostShown = true;
                world.announce("Connection to the host lost. Press Esc, then Main Menu.", World.ALERT);
            }
        } else {
            for (Command c : localCommands) apply(c);
            localCommands.clear();
            boolean running = !paused && !menuOpen && !showHelp && !confirmSurrender;
            if (running) {
                float dt = Math.min(delta, 0.05f) * speed;
                int steps = Math.max(1, (int) Math.ceil(dt / STEP));
                float h = dt / steps;
                for (int i = 0; i < steps; i++) world.update(h);
                // long campaigns: autosave every 5 minutes of game time
                if (world.time >= nextAutosave && !world.gameOver && !world.matchOver) {
                    nextAutosave = world.time + AUTOSAVE_EVERY;
                    try {
                        SaveManager.autosave(world);
                        world.notify(world.player, "Autosaved.", World.INFO);
                    } catch (Exception e) {
                        world.notify(world.player, "Autosave failed: " + e.getMessage(), World.ALERT);
                    }
                }
            }
        }
        input.cleanSelection();
        cam.update();
        ScreenUtils.clear(0.008f, 0.01f, 0.03f, 1f);
        renderer.render();
        hud.render();
        if (world.setup.mode == spacerts.world.GameSetup.Mode.MULTIPLAYER) empiresOverlay();
    }

    /** LAN loading: draws the game underneath (warming up the renderer), reports ready, and covers it all with the loading screen. */
    private void renderLoading(float delta) {
        net.update(delta, this);
        loadTime += Math.min(delta, 0.25f);
        loadFrames++;
        if (simWarm && loadFrames >= LOAD_FRAMES && !net.isLocalReady()) net.markReady();
        cam.update();
        ScreenUtils.clear(0.008f, 0.01f, 0.03f, 1f);
        renderer.render();
        hud.render();
        if (net.go) return; // just started: next frame is the game
        float W = hudCam.viewportWidth, H = hudCam.viewportHeight;
        if (loadSr == null) loadSr = new com.badlogic.gdx.graphics.glutils.ShapeRenderer();
        com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        loadSr.setProjectionMatrix(hudCam.combined);
        loadSr.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        loadSr.setColor(0.01f, 0.015f, 0.04f, 1f);
        loadSr.rect(0, 0, W, H);
        float bw = Math.min(560, W - 80), bx = (W - bw) / 2, by = H * 0.42f;
        float simPart = simWarm ? 1f : Math.min(0.95f, loadTime / 2f);
        float drawPart = Math.min(1f, loadFrames / (float) LOAD_FRAMES);
        float waitPart = net.isLocalReady() ? net.readyCount() / (float) Math.max(1, net.playerCount()) : 0f;
        float progress = 0.15f + 0.35f * simPart + 0.25f * drawPart + 0.25f * waitPart;
        loadSr.setColor(0.12f, 0.14f, 0.22f, 1f);
        loadSr.rect(bx, by, bw, 14);
        loadSr.setColor(0.35f, 0.7f, 1f, 1f);
        loadSr.rect(bx, by, bw * Math.min(1f, progress), 14);
        loadSr.end();
        com.badlogic.gdx.graphics.g2d.BitmapFont f = game.font;
        game.batch.setProjectionMatrix(hudCam.combined);
        game.batch.begin();
        f.getData().setScale(2f);
        f.setColor(0.75f, 0.85f, 1f, 1f);
        f.draw(game.batch, "LOADING", 0, H * 0.62f, W, com.badlogic.gdx.utils.Align.center, false);
        f.getData().setScale(1.05f);
        f.setColor(1f, 1f, 1f, 0.9f);
        String step = !simWarm ? "Generating galaxy and warming up the simulation..."
                : loadFrames < LOAD_FRAMES ? "Preparing the battlefield..."
                : net.isHost() ? "Waiting for players (" + net.readyCount() + "/" + net.playerCount() + " ready)" : "Ready - waiting for the other players...";
        f.draw(game.batch, step, 0, by - 16, W, com.badlogic.gdx.utils.Align.center, false);
        f.getData().setScale(0.9f);
        float y = by - 60;
        for (spacerts.world.Faction fac : world.factions) {
            if (!fac.isMajor()) continue;
            f.setColor(fac.color);
            String line = fac.name + (fac == world.player ? " (you)" : "") + (fac.kind == spacerts.world.Faction.Kind.RIVAL ? "  - " + fac.difficulty.title + " bot" : "");
            f.draw(game.batch, line, 0, y, W, com.badlogic.gdx.utils.Align.center, false);
            y -= 22;
        }
        if (net.hostLost) {
            f.setColor(1f, 0.5f, 0.4f, 1f);
            f.draw(game.batch, "Connection to the host lost - press Esc for the menu", 0, y - 16, W, com.badlogic.gdx.utils.Align.center, false);
        }
        f.getData().setScale(1f);
        game.batch.end();
        if (net.hostLost && com.badlogic.gdx.Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.ESCAPE)) toMenu();
    }

    /** Multiplayer: empires with their team and state at the top right; the LAN wait notice. */
    private void empiresOverlay() {
        com.badlogic.gdx.graphics.g2d.BitmapFont f = game.font;
        float W = hudCam.viewportWidth, H = hudCam.viewportHeight;
        game.batch.setProjectionMatrix(hudCam.combined);
        game.batch.begin();
        f.getData().setScale(0.85f);
        float y = H - 42;
        for (spacerts.world.Faction fac : world.factions) {
            if (!fac.isMajor()) continue;
            String line = fac.name + (fac == world.player ? " (you)" : "") + (fac.team != 0 ? "  [Team " + fac.team + "]" : "")
                    + (fac.kind == spacerts.world.Faction.Kind.RIVAL ? "  " + fac.difficulty.title + " bot" : "")
                    + (fac.eliminated ? "  - eliminated" : "");
            f.setColor(fac.eliminated ? com.badlogic.gdx.graphics.Color.GRAY : fac.color);
            f.draw(game.batch, line, W - 420, y, 410, com.badlogic.gdx.utils.Align.right, false);
            y -= 17;
        }
        if (net != null && (net.waiting || net.hostLost)) {
            f.getData().setScale(1.4f);
            f.setColor(1f, 0.8f, 0.4f, 1f);
            f.draw(game.batch, net.hostLost ? "Connection to the host lost - Esc for the menu" : "Waiting for players...",
                    0, H * 0.62f, W, com.badlogic.gdx.utils.Align.center, false);
        }
        if (paused) {
            f.getData().setScale(1.4f);
            f.setColor(1f, 1f, 1f, 0.9f);
            f.draw(game.batch, "PAUSED", 0, H * 0.68f, W, com.badlogic.gdx.utils.Align.center, false);
        }
        f.getData().setScale(1f);
        game.batch.end();
    }

    private boolean hostLostShown;

    private void apply(Command c) {
        switch (c.type) {
            case PAUSE: paused = c.arg == 1; break;
            case SPEED: speed = Math.max(1, Math.min(4, c.arg)); break;
            default: CommandExecutor.apply(world, c); break;
        }
    }

    /** Lockstep: one turn's commands, then its ticks (none while paused). */
    @Override
    public void runTurn(List<Command> commands) {
        for (Command c : commands) apply(c);
        if (paused) return;
        int ticks = Lockstep.TURN_TICKS * speed;
        for (int i = 0; i < ticks; i++) world.update(Lockstep.STEP);
    }

    @Override
    public World world() {
        return world;
    }

    public void save() {
        if (!canSave()) {
            world.notify(world.player, "Saving is not available in LAN games.", World.ALERT);
            return;
        }
        try {
            SaveManager.save(world);
            world.notify(world.player, "Game saved.", World.GOOD);
        } catch (Exception e) {
            world.notify(world.player, "Save failed: " + e.getMessage(), World.ALERT);
        }
    }

    public void load() {
        if (!canSave()) {
            world.notify(world.player, "Loading is not available in LAN games.", World.ALERT);
            return;
        }
        try {
            World w = SaveManager.load();
            if (w == null) {
                world.notify(world.player, "No saved game found.", World.ALERT);
                return;
            }
            world = w;
            input.reset();
            centerOnMothership();
            world.notify(world.player, "Game loaded.", World.GOOD);
            menuOpen = false;
        } catch (Exception e) {
            world.notify(world.player, "Load failed: " + e.getMessage(), World.ALERT);
        }
    }

    public void toMenu() {
        String msg = null;
        if (net != null) {
            if (net.hostLost) msg = "The host left the game.";
            net.close();
        }
        if (loadSr != null) {
            loadSr.dispose();
            loadSr = null;
        }
        game.setScreen(new MenuScreen(game, msg));
    }

    @Override
    public void resize(int width, int height) {
        float x = cam.position.x, y = cam.position.y;
        cam.setToOrtho(false, width, height);
        cam.position.set(x, y, 0);
        cam.update();
        hudCam.setToOrtho(false, width, height);
    }
}

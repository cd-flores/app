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
        if (net != null) showHelp = false; // the help overlay can't pause a shared game
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
                if (world.time >= nextAutosave && !world.gameOver) {
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

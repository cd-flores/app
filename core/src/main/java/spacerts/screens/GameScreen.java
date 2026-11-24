package spacerts.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.ScreenUtils;
import spacerts.SpaceRtsGame;
import spacerts.input.GameInput;
import spacerts.save.SaveManager;
import spacerts.ui.Hud;
import spacerts.ui.WorldRenderer;
import spacerts.units.Unit;
import spacerts.world.World;

public class GameScreen extends ScreenAdapter {
    public final SpaceRtsGame game;
    public World world;
    public final OrthographicCamera cam = new OrthographicCamera();
    public final OrthographicCamera hudCam = new OrthographicCamera();
    public final GameInput input;
    public final WorldRenderer renderer;
    public final Hud hud;
    public boolean paused, showHelp = true, menuOpen;
    public int speed = 1;
    private static final float STEP = 1f / 30f;

    public GameScreen(SpaceRtsGame game, World world) {
        this.game = game;
        this.world = world;
        cam.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        hudCam.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.zoom = 1.4f;
        input = new GameInput(this);
        renderer = new WorldRenderer(this);
        hud = new Hud(this);
        centerOnMothership();
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

    @Override
    public void render(float delta) {
        input.update(delta);
        boolean running = !paused && !menuOpen && !showHelp;
        if (running) {
            float dt = Math.min(delta, 0.05f) * speed;
            int steps = Math.max(1, (int) Math.ceil(dt / STEP));
            float h = dt / steps;
            for (int i = 0; i < steps; i++) world.update(h);
        }
        input.cleanSelection();
        cam.update();
        ScreenUtils.clear(0.008f, 0.01f, 0.03f, 1f);
        renderer.render();
        hud.render();
    }

    public void save() {
        try {
            SaveManager.save(world);
            world.notify(world.player, "Game saved.", World.GOOD);
        } catch (Exception e) {
            world.notify(world.player, "Save failed: " + e.getMessage(), World.ALERT);
        }
    }

    public void load() {
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
        game.setScreen(new MenuScreen(game));
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
package spacerts.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import spacerts.SpaceRtsGame;
import spacerts.save.SaveManager;
import spacerts.world.GalaxyGenerator;
import spacerts.world.Difficulty;
import spacerts.world.World;

import java.util.Random;

public class MenuScreen extends ScreenAdapter {
    private final SpaceRtsGame game;
    private final OrthographicCamera cam = new OrthographicCamera();
    private final float[] sx = new float[300], sy = new float[300], sz = new float[300];
    private int rivals = 2;
    /** Remembered between games in this session. */
    private static Difficulty difficulty = Difficulty.NORMAL;
    private long seed;
    private float t;
    private String status = "";

    public MenuScreen(SpaceRtsGame game) {
        this.game = game;
        Random r = new Random();
        seed = r.nextInt(999999);
        for (int i = 0; i < sx.length; i++) {
            sx[i] = r.nextFloat();
            sy[i] = r.nextFloat();
            sz[i] = 0.5f + r.nextFloat() * 2f;
        }
        cam.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean keyDown(int key) {
                switch (key) {
                    case Input.Keys.LEFT: rivals = Math.max(0, rivals - 1); break;
                    case Input.Keys.RIGHT: rivals = Math.min(3, rivals + 1); break;
                    case Input.Keys.UP: difficulty = difficulty.easier(); break;
                    case Input.Keys.DOWN: difficulty = difficulty.harder(); break;
                    case Input.Keys.R: seed = new Random().nextInt(999999); break;
                    case Input.Keys.ENTER: start(); break;
                    case Input.Keys.L: load(); break;
                    case Input.Keys.A: loadAutosave(); break;
                    case Input.Keys.ESCAPE: Gdx.app.exit(); break;
                    default: break;
                }
                return true;
            }
        });
    }

    private void start() {
        World w = new World(seed, rivals, difficulty);
        GalaxyGenerator.generate(w);
        game.setScreen(new GameScreen(game, w));
        dispose();
    }

    private void loadAutosave() {
        try {
            World w = SaveManager.loadAutosave();
            if (w == null) {
                status = "No autosave found.";
                return;
            }
            game.setScreen(new GameScreen(game, w));
            dispose();
        } catch (Exception e) {
            status = "Could not load autosave: " + e.getMessage();
        }
    }

    private void load() {
        try {
            World w = SaveManager.load();
            if (w == null) {
                status = "No saved game found.";
                return;
            }
            game.setScreen(new GameScreen(game, w));
            dispose();
        } catch (Exception e) {
            status = "Could not load save: " + e.getMessage();
        }
    }

    @Override
    public void render(float delta) {
        t += delta;
        float W = Gdx.graphics.getWidth(), H = Gdx.graphics.getHeight();
        ScreenUtils.clear(0.01f, 0.01f, 0.035f, 1f);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer sr = game.shapes;
        sr.setProjectionMatrix(cam.combined);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < sx.length; i++) {
            float x = ((sx[i] * W - t * 6 * sz[i]) % W + W) % W;
            float b = 0.3f + 0.5f * (sz[i] / 2.5f);
            sr.setColor(b, b, b + 0.1f, 1f);
            sr.rect(x, sy[i] * H, sz[i], sz[i]);
        }
        // decorative planet
        sr.setColor(0.12f, 0.25f, 0.45f, 1f);
        sr.circle(W * 0.8f, H * 0.3f, 160, 64);
        sr.setColor(0.2f, 0.4f, 0.65f, 1f);
        sr.circle(W * 0.8f - 25, H * 0.3f + 25, 135, 64);
        sr.setColor(1f, 0.85f, 0.45f, 0.08f);
        sr.circle(W * 0.15f, H * 0.82f, 140, 48);
        sr.setColor(1f, 0.85f, 0.45f, 1f);
        sr.circle(W * 0.15f, H * 0.82f, 50, 48);
        sr.end();

        BitmapFont f = game.font;
        game.batch.setProjectionMatrix(cam.combined);
        game.batch.begin();
        f.getData().setScale(3.2f);
        f.setColor(0.4f, 0.85f, 1f, 1f);
        f.draw(game.batch, "NEBULA DOMINION", 0, H * 0.72f, W, Align.center, false);
        f.getData().setScale(1.2f);
        f.setColor(0.7f, 0.75f, 0.85f, 1f);
        f.draw(game.batch, "An endless space RTS", 0, H * 0.72f - 60, W, Align.center, false);
        f.getData().setScale(1.4f);
        f.setColor(Color.WHITE);
        float y = H * 0.5f;
        if (rivals == 0) {
            f.setColor(1f, 0.6f, 0.45f, 1f);
            f.draw(game.batch, "Opponents:  <  Pirates: Hive Galaxy  >      (Left / Right)", 0, y, W, Align.center, false);
            f.getData().setScale(1f);
            f.setColor(0.75f, 0.7f, 0.65f, 1f);
            f.draw(game.batch, "No rival empires. 91 hex systems joined by jump gates; pirate strongholds raid you forever.",
                    0, y - 24, W, Align.center, false);
            f.getData().setScale(1.4f);
            f.setColor(Color.WHITE);
        } else
            f.draw(game.batch, "Rival empires:  <  " + rivals + "  >      (Left / Right)", 0, y, W, Align.center, false);
        f.draw(game.batch, "Galaxy seed:  " + seed + "      (R to reroll)", 0, y - (rivals == 0 ? 52 : 40), W, Align.center, false);
        f.setColor(difficulty == Difficulty.EASY ? new Color(0.5f, 1f, 0.6f, 1f) : difficulty == Difficulty.HARD ? new Color(1f, 0.5f, 0.45f, 1f) : Color.WHITE);
        f.draw(game.batch, "Difficulty:  <  " + difficulty.title + "  >      (Up / Down)", 0, y + 40, W, Align.center, false);
        f.setColor(0.5f, 1f, 0.6f, 1f);
        f.draw(game.batch, "ENTER  -  New Game", 0, y - 110, W, Align.center, false);
        f.setColor(SaveManager.exists() ? new Color(0.6f, 0.85f, 1f, 1f) : Color.GRAY);
        f.draw(game.batch, "L  -  Load Saved Game", 0, y - 150, W, Align.center, false);
        f.setColor(SaveManager.autosaveExists() ? new Color(0.6f, 0.85f, 1f, 1f) : Color.GRAY);
        f.draw(game.batch, "A  -  Load Autosave", 0, y - 190, W, Align.center, false);
        f.setColor(Color.GRAY);
        f.draw(game.batch, "ESC  -  Quit", 0, y - 230, W, Align.center, false);
        f.getData().setScale(1.1f);
        f.setColor(1f, 0.5f, 0.45f, 1f);
        f.draw(game.batch, status, 0, y - 280, W, Align.center, false);
        f.getData().setScale(1f);
        game.batch.end();
    }

    @Override
    public void resize(int width, int height) {
        cam.setToOrtho(false, width, height);
    }
}
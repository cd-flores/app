package spacerts;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import spacerts.screens.MenuScreen;

public class SpaceRtsGame extends Game {
    /**
     * Reads the cursor in window coordinates (top-left origin), even when it is just outside the window (over the title
     * bar or the taskbar), into out[0..1]. Returns false when unavailable or the window is not focused. Set by the
     * desktop launcher; null elsewhere (input then falls back to Gdx.input).
     */
    public interface CursorProbe {
        boolean read(float[] out);
    }

    public static CursorProbe cursor;

    public SpriteBatch batch;
    public ShapeRenderer shapes;
    public BitmapFont font;

    @Override
    public void create() {
        batch = new SpriteBatch();
        shapes = new ShapeRenderer(8000);
        font = new BitmapFont();
        font.setUseIntegerPositions(true);
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        setScreen(new MenuScreen(this));
    }

    @Override
    public void dispose() {
        super.dispose();
        batch.dispose();
        shapes.dispose();
        font.dispose();
    }
}
package spacerts;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import spacerts.screens.MenuScreen;

public class SpaceRtsGame extends Game {
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
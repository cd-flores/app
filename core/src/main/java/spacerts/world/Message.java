package spacerts.world;

import com.badlogic.gdx.graphics.Color;

public class Message {
    public final String text;
    public final float time;
    public final Color color;

    public Message(String text, float time, Color color) {
        this.text = text;
        this.time = time;
        this.color = new Color(color);
    }
}

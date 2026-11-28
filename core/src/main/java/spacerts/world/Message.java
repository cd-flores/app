package spacerts.world;

import com.badlogic.gdx.graphics.Color;

public class Message {
    /** Chat lines stay on screen longer than game messages. */
    public static final float LIFE = 12f, CHAT_LIFE = 20f;
    public final String text;
    public final float time;
    public final Color color;
    public final boolean chat;

    public Message(String text, float time, Color color) {
        this(text, time, color, false);
    }

    public Message(String text, float time, Color color, boolean chat) {
        this.text = text;
        this.time = time;
        this.color = new Color(color);
        this.chat = chat;
    }

    public float life() {
        return chat ? CHAT_LIFE : LIFE;
    }
}

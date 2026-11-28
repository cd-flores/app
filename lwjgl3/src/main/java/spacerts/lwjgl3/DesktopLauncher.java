package spacerts.lwjgl3;

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import org.lwjgl.glfw.GLFW;
import spacerts.SpaceRtsGame;

public class DesktopLauncher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Nebula Dominion");
        // fit the window on smaller screens so its bottom edge (and edge panning there) stays reachable
        Graphics.DisplayMode dm = Lwjgl3ApplicationConfiguration.getDisplayMode();
        int w = Math.min(1600, (int) (dm.width * 0.9f)), h = Math.min(900, (int) (dm.height * 0.82f));
        config.setWindowedMode(w, h);
        config.useVsync(true);
        config.setForegroundFPS(60);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);

        // Edge panning: GLFW keeps reporting the cursor while it sits on the title bar or just past the window's
        // bottom edge, where libGDX's own input stops getting mouse events.
        double[] cx = new double[1], cy = new double[1];
        SpaceRtsGame.cursor = out -> {
            long win = GLFW.glfwGetCurrentContext();
            if (win == 0 || GLFW.glfwGetWindowAttrib(win, GLFW.GLFW_FOCUSED) == 0) return false;
            GLFW.glfwGetCursorPos(win, cx, cy);
            out[0] = (float) cx[0];
            out[1] = (float) cy[0];
            return true;
        };
        new Lwjgl3Application(new SpaceRtsGame(), config);
    }
}

package spacerts.world;

import com.badlogic.gdx.graphics.Color;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything needed to create the same World on every machine: the mode, the seed, the pirate difficulty and the
 * empire slots (humans and bots, their teams, colors and bot difficulties). Plus which slot this machine controls.
 */
public class GameSetup {
    public enum Mode { CAMPAIGN, MULTIPLAYER }

    public static final int MAX_SLOTS = 4;

    public static final Color[] COLORS = {
            new Color(0.3f, 0.8f, 1f, 1f), new Color(1f, 0.33f, 0.3f, 1f), new Color(1f, 0.68f, 0.2f, 1f),
            new Color(0.85f, 0.42f, 1f, 1f), new Color(0.35f, 1f, 0.55f, 1f), new Color(1f, 0.45f, 0.75f, 1f)};
    public static final String[] COLOR_NAMES = {"Cyan", "Red", "Amber", "Violet", "Green", "Pink"};
    public static final String[] BOT_NAMES = {"Krell Dominion", "Ashen Covenant", "Veyra Collective", "Orun Syndicate"};

    public static class Slot {
        public boolean human;
        public String name;
        public Difficulty botDifficulty = Difficulty.NORMAL;
        /** 0 = no team (free-for-all), otherwise 1-4. */
        public int team;
        public int colorIndex;
        public spacerts.units.Race race = spacerts.units.Race.COALITION;

        public Slot(boolean human, String name, int colorIndex) {
            this.human = human;
            this.name = name;
            this.colorIndex = colorIndex;
        }

        public Slot copy() {
            Slot s = new Slot(human, name, colorIndex);
            s.botDifficulty = botDifficulty;
            s.team = team;
            s.race = race;
            return s;
        }
    }

    public Mode mode = Mode.MULTIPLAYER;
    public long seed;
    /** Campaign difficulty; in multiplayer the pirate threat level. */
    public Difficulty difficulty = Difficulty.NORMAL;
    public final List<Slot> slots = new ArrayList<>();
    /** Index of the slot this machine plays (not part of the shared state). */
    public int localSlot;
    /** True when other machines take part (lockstep networking, saves disabled). */
    public boolean networked;

    public static GameSetup campaign(long seed, Difficulty d) {
        GameSetup g = new GameSetup();
        g.mode = Mode.CAMPAIGN;
        g.seed = seed;
        g.difficulty = d;
        g.slots.add(new Slot(true, "Your Empire", 0));
        return g;
    }

    /** The old "player + N rival empires" setup: one human, then bots at the given difficulty. */
    public static GameSetup skirmish(long seed, int rivals, Difficulty d) {
        GameSetup g = new GameSetup();
        g.mode = Mode.MULTIPLAYER;
        g.seed = seed;
        g.difficulty = d;
        g.slots.add(new Slot(true, "Your Empire", 0));
        for (int i = 0; i < Math.min(rivals, MAX_SLOTS - 1); i++) {
            Slot s = new Slot(false, BOT_NAMES[i], i + 1);
            s.botDifficulty = d;
            g.slots.add(s);
        }
        return g;
    }

    public int humans() {
        int n = 0;
        for (Slot s : slots) if (s.human) n++;
        return n;
    }

    public int bots() {
        return slots.size() - humans();
    }

    public GameSetup copy() {
        GameSetup g = new GameSetup();
        g.mode = mode;
        g.seed = seed;
        g.difficulty = difficulty;
        g.localSlot = localSlot;
        g.networked = networked;
        for (Slot s : slots) g.slots.add(s.copy());
        return g;
    }

    // ---- network form (localSlot and networked are per machine and not sent)

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(mode.ordinal());
        out.writeLong(seed);
        out.writeByte(difficulty.ordinal());
        out.writeByte(slots.size());
        for (Slot s : slots) {
            out.writeBoolean(s.human);
            out.writeUTF(s.name);
            out.writeByte(s.botDifficulty.ordinal());
            out.writeByte(s.team);
            out.writeByte(s.colorIndex);
            out.writeByte(s.race.ordinal());
        }
    }

    public static GameSetup read(DataInputStream in) throws IOException {
        GameSetup g = new GameSetup();
        g.mode = Mode.values()[in.readByte()];
        g.seed = in.readLong();
        g.difficulty = Difficulty.values()[in.readByte()];
        int n = in.readByte();
        for (int i = 0; i < n; i++) {
            boolean human = in.readBoolean();
            String name = in.readUTF();
            Slot s = new Slot(human, name, 0);
            s.botDifficulty = Difficulty.values()[in.readByte()];
            s.team = in.readByte();
            s.colorIndex = in.readByte();
            s.race = spacerts.units.Race.of(in.readByte());
            g.slots.add(s);
        }
        return g;
    }
}

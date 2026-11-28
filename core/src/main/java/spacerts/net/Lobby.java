package spacerts.net;

import spacerts.world.Difficulty;
import spacerts.world.GameSetup;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** The pre-game lobby: four seats (open, human, bot or closed), the seed and the pirate threat. Owned by the host. */
public final class Lobby {
    public enum Seat { OPEN, HUMAN, BOT, CLOSED }

    public static final class Entry {
        public Seat seat = Seat.OPEN;
        public String name = "";
        public Difficulty difficulty = Difficulty.NORMAL;
        public int team;
        public int color;
        /** Host side only: the client sitting here (null = the host itself or nobody). */
        public transient Connection conn;
    }

    public final Entry[] entries = new Entry[GameSetup.MAX_SLOTS];
    public long seed;
    public Difficulty pirates = Difficulty.NORMAL;

    public Lobby() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Entry();
            entries[i].color = i;
        }
    }

    /** A fresh lobby with the host in seat 0 and one Normal bot. */
    public static Lobby create(String hostName, long seed) {
        Lobby l = new Lobby();
        l.seed = seed;
        l.entries[0].seat = Seat.HUMAN;
        l.entries[0].name = hostName;
        l.entries[1].seat = Seat.BOT;
        l.entries[1].name = GameSetup.BOT_NAMES[0];
        return l;
    }

    public int filled() {
        int n = 0;
        for (Entry e : entries) if (e.seat == Seat.HUMAN || e.seat == Seat.BOT) n++;
        return n;
    }

    public int remoteHumans() {
        int n = 0;
        for (Entry e : entries) if (e.seat == Seat.HUMAN && e.conn != null) n++;
        return n;
    }

    public int firstOpen() {
        for (int i = 0; i < entries.length; i++) if (entries[i].seat == Seat.OPEN) return i;
        return -1;
    }

    /** A name for a bot in seat i that no other seat uses. */
    public String botName(int i) {
        for (int k = 0; k < GameSetup.BOT_NAMES.length; k++) {
            String n = GameSetup.BOT_NAMES[(i + k) % GameSetup.BOT_NAMES.length];
            boolean used = false;
            for (int j = 0; j < entries.length; j++) if (j != i && n.equals(entries[j].name)) used = true;
            if (!used) return n;
        }
        return "Bot " + (i + 1);
    }

    /** Why the game can't start yet, or null. */
    public String startBlocker() {
        if (filled() < 2) return "Add at least one more player or bot";
        // nobody to fight: everyone on one team
        int team = -1;
        boolean allSame = true;
        for (Entry e : entries) {
            if (e.seat != Seat.HUMAN && e.seat != Seat.BOT) continue;
            if (e.team == 0) { allSame = false; break; }
            if (team == -1) team = e.team;
            else if (team != e.team) allSame = false;
        }
        if (allSame) return "Everyone is on the same team";
        return null;
    }

    /**
     * The game setup for the filled seats, in seat order. seatToSlot receives, for each seat, its slot index in the
     * setup (or -1 for empty seats).
     */
    public GameSetup toSetup(int[] seatToSlot) {
        GameSetup g = new GameSetup();
        g.mode = GameSetup.Mode.MULTIPLAYER;
        g.seed = seed;
        g.difficulty = pirates;
        for (int i = 0; i < entries.length; i++) {
            Entry e = entries[i];
            if (e.seat != Seat.HUMAN && e.seat != Seat.BOT) {
                if (seatToSlot != null) seatToSlot[i] = -1;
                continue;
            }
            if (seatToSlot != null) seatToSlot[i] = g.slots.size();
            GameSetup.Slot s = new GameSetup.Slot(e.seat == Seat.HUMAN, e.name, e.color);
            s.botDifficulty = e.difficulty;
            s.team = e.team;
            g.slots.add(s);
        }
        return g;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(seed);
        out.writeByte(pirates.ordinal());
        for (Entry e : entries) {
            out.writeByte(e.seat.ordinal());
            out.writeUTF(e.name);
            out.writeByte(e.difficulty.ordinal());
            out.writeByte(e.team);
            out.writeByte(e.color);
            out.writeBoolean(e.conn != null);
        }
    }

    /** Client side copy; `remote[i]` tells whether seat i is a joined client (for labels). */
    public static Lobby read(DataInputStream in, boolean[] remote) throws IOException {
        Lobby l = new Lobby();
        l.seed = in.readLong();
        l.pirates = Difficulty.values()[in.readByte()];
        for (int i = 0; i < l.entries.length; i++) {
            Entry e = l.entries[i];
            e.seat = Seat.values()[in.readByte()];
            e.name = in.readUTF();
            e.difficulty = Difficulty.values()[in.readByte()];
            e.team = in.readByte();
            e.color = in.readByte();
            boolean r = in.readBoolean();
            if (remote != null) remote[i] = r;
        }
        return l;
    }
}

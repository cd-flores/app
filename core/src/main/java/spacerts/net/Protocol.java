package spacerts.net;

import spacerts.world.GameSetup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** LAN message types and their payload encoding. Every message is framed as [int length][byte type][payload]. */
public final class Protocol {
    private Protocol() {}

    public static final int TCP_PORT = 47778;
    public static final int DISCOVERY_PORT = 47777;
    /** Bumped whenever the wire format or the simulation changes in a way that breaks lockstep between versions. */
    public static final int VERSION = 7; // 7: Warp Jump target point, loading handshake (READY / GO)
    public static final String DISCOVERY_MAGIC = "NEBULA_DOMINION_LAN";

    /** client -> host: version, player name. */
    public static final byte HELLO = 1;
    /** host -> client: the slot assigned to this client. */
    public static final byte WELCOME = 2;
    /** host -> client: refused (reason). */
    public static final byte REJECT = 3;
    /** host -> all: the lobby setup. */
    public static final byte LOBBY = 4;
    /** client -> host: preferred team and color. */
    public static final byte PREFS = 5;
    /** host -> all: the game starts with this setup. */
    public static final byte START = 6;
    /** client -> host: commands issued by this player. */
    public static final byte CMDS = 7;
    /** host -> all: the commands every machine applies at the start of a turn. */
    public static final byte TURN = 8;
    /** client -> host: the turn this client has finished, and its checksum (or 0 when none). */
    public static final byte ACK = 9;
    /** host -> all: checksums differed at a turn. */
    public static final byte DESYNC = 10;
    /** either way: leaving (host leaving ends the lobby / game). */
    public static final byte BYE = 11;
    /** client -> host: finished loading (world built, warmed up). */
    public static final byte READY = 12;
    /** host -> all: everyone is loaded; turn 0 starts now. */
    public static final byte GO = 13;

    /** A received message; `from` is the connection it came on (host side). */
    public static final class Message {
        public final byte type;
        public final byte[] payload;
        public final Connection from;

        public Message(byte type, byte[] payload, Connection from) {
            this.type = type;
            this.payload = payload;
            this.from = from;
        }

        public DataInputStream in() {
            return new DataInputStream(new ByteArrayInputStream(payload));
        }
    }

    /** Builds a payload with a DataOutputStream. */
    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public static byte[] pack(Writer w) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            if (w != null) w.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hello(String name) {
        return pack(o -> {
            o.writeInt(VERSION);
            o.writeUTF(name);
        });
    }

    public static byte[] setup(GameSetup s) {
        return pack(s::write);
    }

    public static byte[] commands(int turn, List<Command> cmds) {
        return pack(o -> {
            o.writeInt(turn);
            o.writeShort(cmds.size());
            for (Command c : cmds) c.write(o);
        });
    }

    public static List<Command> readCommands(DataInputStream in) throws IOException {
        int n = in.readShort();
        List<Command> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(Command.read(in));
        return list;
    }
}

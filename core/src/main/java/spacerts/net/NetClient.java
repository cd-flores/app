package spacerts.net;

import spacerts.world.GameSetup;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A joining machine: one link to the host. Connecting happens on a background thread so the menu stays live. */
public final class NetClient {
    public final Queue<Protocol.Message> inbox = new ConcurrentLinkedQueue<>();
    public volatile Connection conn;
    public volatile String error;
    public volatile boolean connecting = true;

    /** Lobby state as last sent by the host. */
    public Lobby lobby;
    public final boolean[] remote = new boolean[GameSetup.MAX_SLOTS];
    /** The seat the host gave this client (-1 until welcomed). */
    public int seat = -1;
    /** Set when the host starts the game. */
    public GameSetup started;
    public boolean closedByHost;
    /** Set by close(), even while still connecting: a closed client never sends HELLO or takes a seat. */
    private volatile boolean closed;

    public NetClient(String host, String name) {
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, Protocol.TCP_PORT), 4000);
                if (closed) { // given up on while connecting (for example a double-click on a game)
                    s.close();
                    return;
                }
                Connection c = new Connection(s, inbox);
                conn = c;
                if (closed) { // closed between the check above and publishing conn
                    c.close();
                    return;
                }
                c.send(Protocol.HELLO, Protocol.hello(name));
            } catch (IOException e) {
                error = "Could not connect to " + host + " (" + e.getMessage() + ")";
            } finally {
                connecting = false;
            }
        }, "net-connect");
        t.setDaemon(true);
        t.start();
    }

    /** Lobby phase: reads host messages. Returns true when something changed. */
    public boolean pollLobby() {
        boolean changed = false;
        Protocol.Message m;
        while (started == null && (m = inbox.poll()) != null) {
            try {
                DataInputStream in = m.in();
                switch (m.type) {
                    case Protocol.WELCOME:
                        seat = in.readByte();
                        changed = true;
                        break;
                    case Protocol.REJECT:
                        error = in.readUTF();
                        close();
                        changed = true;
                        break;
                    case Protocol.LOBBY:
                        lobby = Lobby.read(in, remote);
                        changed = true;
                        break;
                    case Protocol.START: {
                        int slot = in.readByte();
                        GameSetup g = GameSetup.read(in);
                        g.localSlot = slot;
                        g.networked = true;
                        started = g;
                        changed = true;
                        break;
                    }
                    case Protocol.BYE:
                        if (error == null) error = "The host closed the game.";
                        closedByHost = true;
                        close();
                        changed = true;
                        break;
                    default:
                        break;
                }
            } catch (IOException e) {
                error = "Bad message from host: " + e.getMessage();
                close();
                changed = true;
            }
        }
        return changed;
    }

    public void sendPrefs(int team, int color) {
        Connection c = conn;
        if (c != null) c.send(Protocol.PREFS, Protocol.pack(o -> {
            o.writeByte(team);
            o.writeByte(color);
        }));
    }

    public void send(byte type, byte[] payload) {
        Connection c = conn;
        if (c != null) c.send(type, payload);
    }

    public boolean isClosed() {
        Connection c = conn;
        return closed || (!connecting && (c == null || c.isClosed()));
    }

    public void close() {
        closed = true;
        Connection c = conn;
        if (c != null) {
            c.send(Protocol.BYE, new byte[0]);
            c.close();
        }
    }
}

package spacerts.net;

import spacerts.world.GameSetup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** The hosting machine: accepts up to three clients, runs the lobby, then relays turns (see Lockstep). */
public final class NetHost {
    public final Queue<Protocol.Message> inbox = new ConcurrentLinkedQueue<>();
    public final List<Connection> clients = new CopyOnWriteArrayList<>();
    public final Lobby lobby;
    private final ServerSocket server;
    private final LanDiscovery.Broadcaster broadcaster;
    private volatile boolean accepting = true;
    private boolean started;

    public NetHost(Lobby lobby) throws IOException {
        this.lobby = lobby;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(Protocol.TCP_PORT));
        broadcaster = new LanDiscovery.Broadcaster(lobby.entries[0].name + "'s game");
        Thread t = new Thread(this::acceptLoop, "net-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (accepting) {
            try {
                Socket s = server.accept();
                if (!accepting) {
                    s.close();
                    break;
                }
                clients.add(new Connection(s, inbox));
            } catch (IOException e) {
                if (!accepting) break;
            }
        }
    }

    /** Lobby phase: handles joins, preferences and leaves. Returns true when the lobby changed. */
    public boolean pollLobby() {
        boolean changed = false;
        Protocol.Message m;
        while ((m = inbox.poll()) != null) {
            Connection c = m.from;
            try {
                switch (m.type) {
                    case Protocol.HELLO: {
                        int version = m.in().readInt();
                        String name = readName(m);
                        if (version != Protocol.VERSION) {
                            reject(c, "Different game version - update both copies");
                            break;
                        }
                        int seat = lobby.firstOpen();
                        if (started || seat < 0) {
                            reject(c, started ? "The game has already started" : "The game is full (4 players)");
                            break;
                        }
                        Lobby.Entry e = lobby.entries[seat];
                        e.seat = Lobby.Seat.HUMAN;
                        e.name = uniqueName(name, seat);
                        e.conn = c;
                        c.slot = seat;
                        c.name = e.name;
                        c.send(Protocol.WELCOME, Protocol.pack(o -> o.writeByte(seat)));
                        changed = true;
                        break;
                    }
                    case Protocol.PREFS: {
                        if (c.slot < 0) break;
                        Lobby.Entry e = lobby.entries[c.slot];
                        java.io.DataInputStream in = m.in();
                        e.team = Math.max(0, Math.min(4, in.readByte()));
                        e.color = Math.floorMod(in.readByte(), GameSetup.COLORS.length);
                        changed = true;
                        break;
                    }
                    case Protocol.BYE:
                        changed |= dropFromLobby(c);
                        break;
                    default:
                        break;
                }
            } catch (IOException e) {
                changed |= dropFromLobby(c);
            }
        }
        if (changed) broadcastLobby();
        broadcaster.update(lobby.entries[0].name + "'s game", lobby.filled());
        return changed;
    }

    private static String readName(Protocol.Message m) throws IOException {
        java.io.DataInputStream in = m.in();
        in.readInt();
        String n = in.readUTF().trim();
        if (n.isEmpty()) n = "Player";
        return n.length() > 20 ? n.substring(0, 20) : n;
    }

    private String uniqueName(String name, int seat) {
        String n = name;
        for (int k = 2; ; k++) {
            boolean used = false;
            for (int i = 0; i < lobby.entries.length; i++) if (i != seat && n.equals(lobby.entries[i].name)) used = true;
            if (!used) return n;
            n = name + " " + k;
        }
    }

    private void reject(Connection c, String why) {
        c.send(Protocol.REJECT, Protocol.pack(o -> o.writeUTF(why)));
        c.close();
        clients.remove(c);
    }

    private boolean dropFromLobby(Connection c) {
        clients.remove(c);
        c.close();
        if (c.slot < 0) return false;
        Lobby.Entry e = lobby.entries[c.slot];
        if (e.conn == c) {
            e.conn = null;
            e.seat = Lobby.Seat.OPEN;
            e.name = "";
            e.team = 0;
        }
        c.slot = -1;
        return true;
    }

    /** Removes a joined player from their seat (host's choice). */
    public void kick(int seat) {
        Lobby.Entry e = lobby.entries[seat];
        if (e.conn != null) {
            Connection c = e.conn;
            reject(c, "Removed from the lobby by the host");
            e.conn = null;
        }
        broadcastLobby();
    }

    public void broadcastLobby() {
        byte[] data = Protocol.pack(lobby::write);
        for (Connection c : clients) if (c.slot >= 0) c.send(Protocol.LOBBY, data);
    }

    /** Sends START to everyone (each with its own slot) and stops accepting. Returns the host's setup. */
    public GameSetup start() {
        started = true;
        accepting = false;
        broadcaster.stop();
        try {
            server.close();
        } catch (IOException ignored) {
        }
        int[] seatToSlot = new int[lobby.entries.length];
        GameSetup setup = lobby.toSetup(seatToSlot);
        setup.networked = true;
        for (Connection c : clients) {
            if (c.slot < 0) {
                c.close();
                continue;
            }
            int slot = seatToSlot[c.slot];
            c.slot = slot; // from now on: the setup slot (= faction id) this client plays
            c.send(Protocol.START, Protocol.pack(o -> {
                o.writeByte(slot);
                setup.write(o);
            }));
        }
        setup.localSlot = seatToSlot[0];
        return setup;
    }

    public void broadcast(byte type, byte[] payload) {
        for (Connection c : clients) c.send(type, payload);
    }

    public void close() {
        accepting = false;
        broadcaster.stop();
        for (Connection c : clients) {
            c.send(Protocol.BYE, new byte[0]);
            c.close();
        }
        clients.clear();
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }
}

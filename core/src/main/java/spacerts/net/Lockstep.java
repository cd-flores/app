package spacerts.net;

import spacerts.units.Unit;
import spacerts.world.Faction;
import spacerts.world.World;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic lockstep over the LAN. Every machine runs the same simulation from the same setup; only player
 * commands travel. The host stamps every command with the turn it takes effect (DELAY turns ahead) and broadcasts
 * one bundle per turn; each machine runs turn T only once it holds bundle T, applying its commands before the
 * turn's ticks. Checksums are compared every HASH_EVERY turns to catch desyncs.
 */
public final class Lockstep {
    /** Simulation ticks per turn at speed 1, and the tick length. */
    public static final int TURN_TICKS = 3;
    public static final float STEP = 1f / 30f;
    public static final float TURN_TIME = TURN_TICKS * STEP;
    /** Turns between issuing a command and it taking effect (~200 ms). */
    public static final int DELAY = 2;
    public static final int HASH_EVERY = 30;
    /** The host waits when a client falls this many turns behind. */
    public static final int MAX_BEHIND = 25;

    /** What the game screen does with a turn. */
    public interface Runner {
        void runTurn(List<Command> commands);

        World world();
    }

    private final NetHost host;
    private final NetClient client;
    private final Map<Integer, List<Command>> bundles = new HashMap<>();
    private final List<Command> outgoing = new ArrayList<>();
    private final Map<Integer, Long> myHashes = new HashMap<>(), peerHashes = new HashMap<>();
    private final Map<Connection, Integer> acked = new HashMap<>();
    /** Next turn to run. */
    public int turn;
    /** Host: next bundle to send. */
    private int nextBundle = DELAY;
    private float clock;

    /** True while the game is stalled waiting for a bundle (client) or a lagging client (host). */
    public boolean waiting;
    /** Loading: false until every machine has loaded (host: all READY; client: GO received). No turns run before. */
    public boolean go;
    /** This machine has finished loading. */
    private boolean localReady;
    private final java.util.Set<Connection> readyClients = new java.util.HashSet<>();
    /** Host: seconds spent waiting in the loading screen. */
    private float loadingTime;
    /** Host: after this long, players still loading are dropped (a bot takes over) and the game starts. */
    public static float READY_TIMEOUT = 60f;
    public boolean hostLost;
    public int desyncTurn = -1;

    private Lockstep(NetHost host, NetClient client) {
        this.host = host;
        this.client = client;
        if (host != null) for (Connection c : host.clients) acked.put(c, -1);
    }

    public static Lockstep host(NetHost h) {
        return new Lockstep(h, null);
    }

    public static Lockstep client(NetClient c) {
        return new Lockstep(null, c);
    }

    public boolean isHost() {
        return host != null;
    }

    /** Queues a local command; it reaches every machine's simulation DELAY turns from now. */
    public void submit(Command c) {
        if (host != null) outgoing.add(c);
        else client.send(Protocol.CMDS, Protocol.commands(turn, Collections.singletonList(c)));
    }

    /** This machine finished loading: clients tell the host; the host counts itself in. */
    public void markReady() {
        if (localReady) return;
        localReady = true;
        if (client != null) client.send(Protocol.READY, new byte[0]);
    }

    public boolean isLocalReady() {
        return localReady;
    }

    /** Players ready / players in the game (host's view; a client only knows about itself). */
    public int readyCount() {
        if (host == null) return localReady ? 1 : 0;
        int n = localReady ? 1 : 0;
        for (Connection c : host.clients) if (!c.isClosed() && readyClients.contains(c)) n++;
        return n;
    }

    public int playerCount() {
        return host == null ? 1 : 1 + connectedClients();
    }

    /** Host, while loading: start once everyone is ready, or drop the stragglers after READY_TIMEOUT. */
    private void hostLoading(float delta) {
        loadingTime += Math.min(delta, 0.25f);
        if (!localReady) return;
        boolean all = true;
        for (Connection c : host.clients) if (!c.isClosed() && !readyClients.contains(c)) all = false;
        if (!all && loadingTime < READY_TIMEOUT) return;
        if (!all) // still loading after the timeout: they leave, and a bot takes over their empire
            for (Connection c : host.clients)
                if (!c.isClosed() && !readyClients.contains(c)) {
                    host.clients.remove(c);
                    acked.remove(c);
                    c.send(Protocol.BYE, new byte[0]);
                    c.close();
                    if (c.slot >= 0) {
                        outgoing.add(new Command(Command.Type.LEAVE, c.slot));
                        c.slot = -1;
                    }
                }
        go = true;
        clock = 0;
        host.broadcast(Protocol.GO, new byte[0]);
    }

    public void update(float delta, Runner r) {
        poll(r.world());
        if (!go) { // loading: nothing runs until every machine is ready
            if (host != null) hostLoading(delta);
            waiting = false;
            if (!go) return;
        }
        clock = Math.min(clock + Math.min(delta, 0.25f), TURN_TIME * 8);
        waiting = false;
        if (host != null) {
            while (clock >= TURN_TIME) {
                if (slowestClient() < turn - MAX_BEHIND) {
                    waiting = true;
                    clock = TURN_TIME;
                    break;
                }
                clock -= TURN_TIME;
                List<Command> bundle = new ArrayList<>(outgoing);
                outgoing.clear();
                bundles.put(nextBundle, bundle);
                host.broadcast(Protocol.TURN, Protocol.commands(nextBundle, bundle));
                nextBundle++;
                run(r);
            }
        } else {
            int ran = 0;
            while (clock >= TURN_TIME || (bundles.size() > DELAY + 2 && ran < 6)) { // catch up when behind
                if (turn >= DELAY && !bundles.containsKey(turn)) {
                    waiting = !hostLost;
                    clock = Math.min(clock, TURN_TIME);
                    break;
                }
                if (clock >= TURN_TIME) clock -= TURN_TIME;
                run(r);
                ran++;
            }
        }
    }

    private void run(Runner r) {
        List<Command> cmds = turn < DELAY ? Collections.emptyList() : bundles.remove(turn);
        if (cmds == null) cmds = Collections.emptyList();
        r.runTurn(cmds);
        int done = turn++;
        boolean hashTurn = done % HASH_EVERY == 0;
        long h = hashTurn ? checksum(r.world()) : 0;
        if (hashTurn) {
            myHashes.put(done, h);
            compare(done);
        }
        if (client != null) {
            final long hh = h;
            client.send(Protocol.ACK, Protocol.pack(o -> {
                o.writeInt(done);
                o.writeBoolean(hashTurn);
                o.writeLong(hh);
            }));
        }
    }

    private void compare(int t) {
        Long a = myHashes.get(t), b = peerHashes.get(t);
        if (a == null || b == null) return;
        peerHashes.remove(t); // keep ours: other clients report the same turn
        if (myHashes.size() > 40) myHashes.keySet().removeIf(k -> k < turn - HASH_EVERY * 30);
        if (!a.equals(b) && desyncTurn < 0) {
            desyncTurn = t;
            if (host != null) host.broadcast(Protocol.DESYNC, Protocol.pack(o -> o.writeInt(t)));
        }
    }

    private int slowestClient() {
        int min = Integer.MAX_VALUE;
        for (Map.Entry<Connection, Integer> e : acked.entrySet()) if (!e.getKey().isClosed()) min = Math.min(min, e.getValue());
        return min;
    }

    private void poll(World w) {
        java.util.Queue<Protocol.Message> inbox = host != null ? host.inbox : client.inbox;
        Protocol.Message m;
        while ((m = inbox.poll()) != null) {
            try {
                DataInputStream in = m.in();
                switch (m.type) {
                    case Protocol.CMDS: { // host: a client's commands go into the next bundle, stamped with its faction
                        if (host == null || m.from.slot < 0) break;
                        in.readInt();
                        for (Command c : Protocol.readCommands(in)) {
                            c.faction = m.from.slot;
                            if (c.type != Command.Type.LEAVE) outgoing.add(c);
                        }
                        break;
                    }
                    case Protocol.ACK: {
                        if (host == null) break;
                        int t = in.readInt();
                        boolean hasHash = in.readBoolean();
                        long h = in.readLong();
                        acked.put(m.from, t);
                        if (hasHash && !peerHashes.containsKey(t)) {
                            peerHashes.put(t, h);
                            compare(t);
                        } else if (hasHash && peerHashes.get(t) != h && desyncTurn < 0) {
                            desyncTurn = t; // two clients disagree with each other
                            final int dt = t;
                            host.broadcast(Protocol.DESYNC, Protocol.pack(o -> o.writeInt(dt)));
                        }
                        break;
                    }
                    case Protocol.TURN: {
                        if (client == null) break;
                        int t = in.readInt();
                        bundles.put(t, Protocol.readCommands(in));
                        break;
                    }
                    case Protocol.READY:
                        if (host != null) readyClients.add(m.from);
                        break;
                    case Protocol.GO:
                        if (client != null && !go) {
                            go = true;
                            clock = 0;
                        }
                        break;
                    case Protocol.DESYNC:
                        if (desyncTurn < 0) desyncTurn = in.readInt();
                        break;
                    case Protocol.BYE:
                        if (host != null) {
                            Connection c = m.from;
                            host.clients.remove(c);
                            acked.remove(c);
                            c.close();
                            if (c.slot >= 0) { // a bot takes over their empire, on every machine at the same turn
                                Command leave = new Command(Command.Type.LEAVE, c.slot);
                                outgoing.add(leave);
                                c.slot = -1;
                            }
                        } else hostLost = true;
                        break;
                    default:
                        break;
                }
            } catch (IOException e) {
                // a malformed message: ignore it
            }
        }
        if (client != null && client.isClosed()) hostLost = true;
    }

    /** Players (other than the host) still connected. */
    public int connectedClients() {
        if (host == null) return 0;
        int n = 0;
        for (Connection c : host.clients) if (!c.isClosed()) n++;
        return n;
    }

    public void close() {
        if (host != null) host.close();
        if (client != null) client.close();
    }

    /** A fingerprint of the simulation state; equal on every machine while they stay in sync. */
    public static long checksum(World w) {
        long h = 1125899906842597L;
        h = mix(h, Float.floatToIntBits(w.time));
        h = mix(h, w.nextId);
        h = mix(h, w.units.size());
        h = mix(h, w.bodies.size());
        for (Unit u : w.units) {
            h = mix(h, u.id);
            h = mix(h, Float.floatToIntBits(u.x));
            h = mix(h, Float.floatToIntBits(u.y));
            h = mix(h, Float.floatToIntBits(u.hp));
            h = mix(h, u.order.ordinal());
        }
        for (Faction f : w.factions) {
            h = mix(h, Double.doubleToLongBits(f.energy));
            h = mix(h, Double.doubleToLongBits(f.metal));
            h = mix(h, f.techs.size());
        }
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h ^ (h >>> 29);
    }
}

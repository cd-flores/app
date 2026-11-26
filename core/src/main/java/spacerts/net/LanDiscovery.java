package spacerts.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds LAN games: hosts broadcast a small UDP packet every second, the Join screen listens for them. */
public final class LanDiscovery {
    private LanDiscovery() {}

    /** A game heard on the LAN. */
    public static final class Found {
        public final String address, name;
        public final int players, port;
        public final long seenAt;

        Found(String address, String name, int players, int port) {
            this.address = address;
            this.name = name;
            this.players = players;
            this.port = port;
            this.seenAt = System.currentTimeMillis();
        }
    }

    /** Host side: announces the game until stopped. */
    public static final class Broadcaster {
        private volatile boolean running = true;
        private volatile String name;
        private volatile int players;

        public Broadcaster(String name) {
            this.name = name;
            Thread t = new Thread(this::run, "lan-broadcast");
            t.setDaemon(true);
            t.start();
        }

        public void update(String name, int players) {
            this.name = name;
            this.players = players;
        }

        public void stop() {
            running = false;
        }

        private void run() {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                while (running) {
                    String msg = Protocol.DISCOVERY_MAGIC + "|" + Protocol.VERSION + "|" + name.replace('|', '/') + "|" + players + "|" + Protocol.TCP_PORT;
                    byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                    for (InetAddress a : broadcastAddresses()) {
                        try {
                            s.send(new DatagramPacket(data, data.length, a, Protocol.DISCOVERY_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {
                // discovery is best effort; joining by IP still works
            }
        }
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null && !out.contains(ia.getBroadcast())) out.add(ia.getBroadcast());
            }
            out.add(InetAddress.getLoopbackAddress()); // a second copy of the game on this computer
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Join side: collects announced games until stopped. */
    public static final class Listener {
        private volatile boolean running = true;
        private final Map<String, Found> found = Collections.synchronizedMap(new LinkedHashMap<>());
        private DatagramSocket socket;
        public volatile String error;

        public Listener() {
            Thread t = new Thread(this::run, "lan-listen");
            t.setDaemon(true);
            t.start();
        }

        /** Games heard in the last few seconds. */
        public List<Found> games() {
            long now = System.currentTimeMillis();
            List<Found> out = new ArrayList<>();
            synchronized (found) {
                for (Found f : found.values()) if (now - f.seenAt < 4000) out.add(f);
            }
            return out;
        }

        public void stop() {
            running = false;
            if (socket != null) socket.close();
        }

        private void run() {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(Protocol.DISCOVERY_PORT));
                socket.setSoTimeout(500);
                byte[] buf = new byte[512];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String[] parts = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\\|");
                    if (parts.length < 5 || !Protocol.DISCOVERY_MAGIC.equals(parts[0])) continue;
                    if (!String.valueOf(Protocol.VERSION).equals(parts[1])) continue;
                    String addr = p.getAddress().getHostAddress();
                    try {
                        found.put(addr, new Found(addr, parts[2], Integer.parseInt(parts[3]), Integer.parseInt(parts[4])));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException e) {
                if (running) error = "LAN search unavailable (" + e.getMessage() + ") - enter the host's IP instead.";
            } finally {
                if (socket != null) socket.close();
            }
        }
    }
}

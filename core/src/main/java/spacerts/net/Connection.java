package spacerts.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Queue;

/** One TCP link. A daemon thread reads framed messages into a shared inbox; sends are synchronized. */
public final class Connection {
    private static final int MAX_FRAME = 4 << 20;

    private final Socket socket;
    private final DataOutputStream out;
    private volatile boolean closed;
    /** Host side: the lobby slot this connection plays (-1 until assigned). */
    public volatile int slot = -1;
    public volatile String name = "";

    public Connection(Socket socket, Queue<Protocol.Message> inbox) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        Thread reader = new Thread(() -> {
            try {
                while (!closed) {
                    int len = in.readInt();
                    if (len < 1 || len > MAX_FRAME) throw new IOException("Bad frame length " + len);
                    byte type = in.readByte();
                    byte[] payload = new byte[len - 1];
                    in.readFully(payload);
                    inbox.add(new Protocol.Message(type, payload, this));
                }
            } catch (IOException e) {
                // fall through: the link is gone
            } finally {
                boolean was = closed;
                close();
                if (!was) inbox.add(new Protocol.Message(Protocol.BYE, new byte[0], this));
            }
        }, "net-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void send(byte type, byte[] payload) {
        if (closed) return;
        try {
            out.writeInt(payload.length + 1);
            out.writeByte(type);
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public String address() {
        return socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "?";
    }
}

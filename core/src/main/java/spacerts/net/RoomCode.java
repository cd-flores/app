package spacerts.net;

/**
 * Room codes: a host's IPv4 address packed into 7 easy-to-type characters, shown as "ABC-DEFG".
 * No server is involved: the code is the address, so it works on the same network (or a shared VPN
 * such as Radmin, ZeroTier or Hamachi). The 32 address bits plus a 3-bit checksum fill 7 base-32 digits,
 * so most typos are caught before connecting.
 */
public final class RoomCode {
    /** Crockford-style base 32: no I, L, O or U, so codes can't be misread. */
    private static final String DIGITS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int LENGTH = 7;

    private RoomCode() {
    }

    /** Code for a dotted IPv4 address ("192.168.1.23"), or null if it isn't one. */
    public static String encode(String ipv4) {
        long ip = parseIp(ipv4);
        if (ip < 0) return null;
        long v = (ip << 3) | checksum(ip);
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = DIGITS.charAt((int) (v & 31));
            v >>>= 5;
        }
        String s = new String(out);
        return s.substring(0, 3) + "-" + s.substring(3);
    }

    /**
     * The IPv4 address a code stands for, or null when it isn't a valid code (wrong length, unknown
     * characters, or a failed checksum). Case, spaces and dashes don't matter; O reads as 0 and I / L as 1.
     */
    public static String decode(String code) {
        String s = normalize(code);
        if (s.length() != LENGTH) return null;
        long v = 0;
        for (int i = 0; i < LENGTH; i++) {
            int d = DIGITS.indexOf(s.charAt(i));
            if (d < 0) return null;
            v = (v << 5) | d;
        }
        long ip = v >>> 3;
        if ((v & 7) != checksum(ip)) return null;
        return ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255);
    }

    /** Upper-case, drop spaces and dashes, and fix look-alike characters. */
    public static String normalize(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : code.toUpperCase().toCharArray()) {
            if (c == '-' || c == ' ' || c == '_') continue;
            if (c == 'O') c = '0';
            else if (c == 'I' || c == 'L') c = '1';
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * What the player typed in the join box, as an address to connect to: a room code, or a plain IP / host name
     * for players who still know the address. Null when it's neither.
     */
    public static String resolve(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty()) return null;
        String ip = decode(t);
        if (ip != null) return ip;
        if (t.indexOf('.') >= 0 || t.equalsIgnoreCase("localhost")) return t; // an IP address or host name
        return null;
    }

    /** 3 check bits from a well-mixed hash of every address bit (MurmurHash3's finalizer), so any change shows. */
    private static int checksum(long ip) {
        int h = (int) ip ^ 0x5bd1e995;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h & 7;
    }

    private static long parseIp(String s) {
        if (s == null) return -1;
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return -1;
        long v = 0;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3) return -1;
            int n;
            try {
                n = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (n < 0 || n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }
}

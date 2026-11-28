package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.net.RoomCode;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Room codes: a host's LAN address as a short code friends can type instead of the IP. */
class RoomCodeTest {

    @Test
    void codesRoundTripToTheSameAddress() {
        String[] ips = {"192.168.1.23", "10.0.0.5", "172.16.4.200", "26.12.34.56", "0.0.0.0", "255.255.255.255", "127.0.0.1"};
        for (String ip : ips) {
            String code = RoomCode.encode(ip);
            assertNotNull(code, ip);
            assertTrue(code.matches("[0-9A-Z]{3}-[0-9A-Z]{4}"), "format: " + code);
            assertEquals(ip, RoomCode.decode(code), "round trip of " + ip);
        }
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            String ip = r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256);
            assertEquals(ip, RoomCode.decode(RoomCode.encode(ip)));
        }
    }

    @Test
    void typingIsForgiving() {
        String code = RoomCode.encode("192.168.1.23");
        String plain = code.replace("-", "");
        assertEquals("192.168.1.23", RoomCode.decode(plain.toLowerCase()), "lower case, no dash");
        assertEquals("192.168.1.23", RoomCode.decode("  " + code.substring(0, 3) + " " + code.substring(4) + " "), "spaces");
        // the alphabet has no O, I or L, so those read as 0 and 1
        String withLookalikes = plain.replace('0', 'O').replace('1', 'I');
        assertEquals("192.168.1.23", RoomCode.decode(withLookalikes));
    }

    @Test
    void mostTyposAreCaught() {
        Random r = new Random(11);
        int caught = 0, tries = 0;
        for (int i = 0; i < 400; i++) {
            String ip = r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256);
            char[] c = RoomCode.encode(ip).replace("-", "").toCharArray();
            int pos = r.nextInt(c.length);
            char was = c[pos];
            char now = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".charAt(r.nextInt(32));
            if (now == was) continue;
            c[pos] = now;
            tries++;
            String back = RoomCode.decode(new String(c));
            if (back == null) caught++;
            else assertNotEquals(ip, back);
        }
        assertTrue(caught > tries * 0.75, "checksum catches most single-character typos: " + caught + "/" + tries);
        assertNull(RoomCode.decode("ABC"), "too short");
        assertNull(RoomCode.decode("ABC-DEFGH"), "too long");
        assertNull(RoomCode.decode("AB!-DEFG"), "bad character");
    }

    @Test
    void theJoinBoxTakesCodesOrAddresses() {
        String code = RoomCode.encode("192.168.0.42");
        assertEquals("192.168.0.42", RoomCode.resolve(code));
        assertEquals("192.168.0.42", RoomCode.resolve("192.168.0.42"), "a plain IP still works");
        assertEquals("localhost", RoomCode.resolve("localhost"));
        assertNull(RoomCode.resolve(""));
        assertNull(RoomCode.resolve("hello"), "neither a code nor an address");
        assertNull(RoomCode.encode("not.an.ip.address"));
        assertNull(RoomCode.encode("300.1.1.1"));
    }
}

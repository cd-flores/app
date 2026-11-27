package spacerts.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import spacerts.SpaceRtsGame;
import spacerts.net.LanDiscovery;
import spacerts.net.Lobby;
import spacerts.net.Lockstep;
import spacerts.net.NetClient;
import spacerts.net.RoomCode;
import spacerts.net.NetHost;
import spacerts.save.SaveManager;
import spacerts.world.Difficulty;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Main menu: Campaign (the pirate Hive), Multiplayer (LAN lobby with up to 4 humans or bots, teams and per-bot
 * difficulty; host with only bots to play offline), loading saves. Mouse or keyboard (arrows, Enter, Esc).
 */
public class MenuScreen extends ScreenAdapter {
    private enum Page { MAIN, CAMPAIGN, MULTIPLAYER, JOIN, LOBBY }

    private final SpaceRtsGame game;
    private final OrthographicCamera cam = new OrthographicCamera();
    private final float[] sx = new float[300], sy = new float[300], sz = new float[300];
    private final GlyphLayout layout = new GlyphLayout();
    private final Preferences prefs;

    /** Remembered between games in this session. */
    private static Difficulty difficulty = Difficulty.NORMAL;
    private long seed;
    private float t;
    private String status = "";
    private Page page = Page.MAIN;

    // multiplayer
    private final StringBuilder playerName = new StringBuilder(), joinCode = new StringBuilder();
    private NetHost host;
    /** The lobby when hosting (with or without a working LAN host). */
    private Lobby lobby;
    private NetClient client;
    private LanDiscovery.Listener listener;
    /** The host's room code(s): the first is the main network, any others are extra networks (VPNs). */
    private String roomCode, otherCodes;

    // widgets rebuilt every frame
    private final List<Widget> widgets = new ArrayList<>();
    private int focus;
    private Page builtFor;

    private static final Color PANEL = new Color(0.05f, 0.08f, 0.16f, 0.88f);
    private static final Color BTN = new Color(0.09f, 0.16f, 0.3f, 0.95f);
    private static final Color BTN_HOT = new Color(0.16f, 0.32f, 0.55f, 1f);
    private static final Color BTN_OFF = new Color(0.08f, 0.09f, 0.13f, 0.9f);
    private static final Color EDGE = new Color(0.35f, 0.7f, 1f, 1f);
    private static final Color TITLE = new Color(0.4f, 0.85f, 1f, 1f);
    private static final Color DIM = new Color(0.62f, 0.68f, 0.8f, 1f);
    private static final Color WARN = new Color(1f, 0.55f, 0.45f, 1f);
    private static final Color GOOD = new Color(0.5f, 1f, 0.6f, 1f);

    public MenuScreen(SpaceRtsGame game) {
        this(game, null);
    }

    public MenuScreen(SpaceRtsGame game, String message) {
        this.game = game;
        Random r = new Random();
        seed = r.nextInt(999999);
        for (int i = 0; i < sx.length; i++) {
            sx[i] = r.nextFloat();
            sy[i] = r.nextFloat();
            sz[i] = 0.5f + r.nextFloat() * 2f;
        }
        cam.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        prefs = Gdx.app.getPreferences("nebula-dominion");
        playerName.append(prefs.getString("playerName", "Commander"));
        joinCode.append(prefs.getString("joinCode", ""));
        if (message != null) status = message;
    }

    // ------------------------------------------------------------------ widgets

    private enum Kind { BUTTON, SELECT, TEXT, LABEL }

    private static final class Widget {
        Kind kind;
        float x, y, w, h;
        String label, value;
        Runnable click;
        IntConsumer change;
        StringBuilder text;
        int maxLen;
        boolean enabled = true;
        Color color;
        float scale = 1.25f;

        boolean focusable() {
            return kind != Kind.LABEL && enabled;
        }

        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private Widget button(float x, float y, float w, float h, String label, Runnable click) {
        Widget b = new Widget();
        b.kind = Kind.BUTTON;
        b.x = x; b.y = y; b.w = w; b.h = h;
        b.label = label;
        b.click = click;
        widgets.add(b);
        return b;
    }

    /** "label   < value >": click the left or right half (or Left / Right) to step through values. */
    private Widget select(float x, float y, float w, float h, String label, String value, IntConsumer change) {
        Widget b = new Widget();
        b.kind = Kind.SELECT;
        b.x = x; b.y = y; b.w = w; b.h = h;
        b.label = label;
        b.value = value;
        b.change = change;
        widgets.add(b);
        return b;
    }

    private Widget text(float x, float y, float w, float h, String label, StringBuilder sb, int maxLen) {
        Widget b = new Widget();
        b.kind = Kind.TEXT;
        b.x = x; b.y = y; b.w = w; b.h = h;
        b.label = label;
        b.text = sb;
        b.maxLen = maxLen;
        widgets.add(b);
        return b;
    }

    private Widget label(float x, float y, float w, float h, String s, Color c, float scale) {
        Widget b = new Widget();
        b.kind = Kind.LABEL;
        b.x = x; b.y = y; b.w = w; b.h = h;
        b.label = s;
        b.color = c;
        b.scale = scale;
        widgets.add(b);
        return b;
    }

    private List<Widget> focusables() {
        List<Widget> out = new ArrayList<>();
        for (Widget w : widgets) if (w.focusable()) out.add(w);
        return out;
    }

    private Widget focused() {
        List<Widget> f = focusables();
        if (f.isEmpty()) return null;
        focus = Math.floorMod(focus, f.size());
        return f.get(focus);
    }

    private void go(Page p) {
        if (p != Page.JOIN && listener != null) {
            listener.stop();
            listener = null;
        }
        if (p == Page.JOIN && listener == null) listener = new LanDiscovery.Listener();
        page = p;
        focus = 0;
        status = "";
    }

    // ------------------------------------------------------------------ input

    @Override
    public void show() {
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override
            public boolean keyDown(int key) {
                Widget f = focused();
                switch (key) {
                    case Input.Keys.UP: focus--; return true;
                    case Input.Keys.DOWN:
                    case Input.Keys.TAB: focus++; return true;
                    case Input.Keys.LEFT:
                        if (f != null && f.kind == Kind.SELECT) f.change.accept(-1);
                        else focus--;
                        return true;
                    case Input.Keys.RIGHT:
                        if (f != null && f.kind == Kind.SELECT) f.change.accept(1);
                        else focus++;
                        return true;
                    case Input.Keys.ENTER:
                    case Input.Keys.NUMPAD_ENTER:
                        if (f != null) activate(f, 1);
                        return true;
                    case Input.Keys.ESCAPE: back(); return true;
                    case Input.Keys.BACKSPACE:
                        if (f != null && f.kind == Kind.TEXT && f.text.length() > 0) f.text.setLength(f.text.length() - 1);
                        return true;
                    default: return false;
                }
            }

            @Override
            public boolean keyTyped(char c) {
                Widget f = focused();
                if (f == null || f.kind != Kind.TEXT) return false;
                if (c >= 32 && c < 127 && c != '|' && f.text.length() < f.maxLen) f.text.append(c);
                return true;
            }

            @Override
            public boolean mouseMoved(int mx, int my) {
                hover(mx, Gdx.graphics.getHeight() - my);
                return false;
            }

            @Override
            public boolean touchDown(int mx, int my, int pointer, int button) {
                float px = mx, py = Gdx.graphics.getHeight() - my;
                List<Widget> fs = focusables();
                for (int i = 0; i < fs.size(); i++) {
                    Widget w = fs.get(i);
                    if (!w.contains(px, py)) continue;
                    focus = i;
                    int dir = button == Input.Buttons.RIGHT ? -1 : 1;
                    if (w.kind == Kind.SELECT) { // left half of the value steps back, right half forward
                        boolean labelled = w.label != null && !w.label.isEmpty();
                        dir = px < w.x + w.w * (labelled ? 0.67f : 0.5f) ? -1 : 1;
                    }
                    activate(w, dir);
                    return true;
                }
                return true;
            }
        });
    }

    private void hover(float px, float py) {
        List<Widget> fs = focusables();
        for (int i = 0; i < fs.size(); i++)
            if (fs.get(i).contains(px, py)) {
                focus = i;
                return;
            }
    }

    private void activate(Widget w, int dir) {
        if (w.kind == Kind.BUTTON && w.click != null) w.click.run();
        else if (w.kind == Kind.SELECT) w.change.accept(dir);
        else if (w.kind == Kind.TEXT) focus++; // Enter moves on from a text field
    }

    private void back() {
        switch (page) {
            case MAIN: Gdx.app.exit(); break;
            case CAMPAIGN:
            case MULTIPLAYER: go(Page.MAIN); break;
            case JOIN: go(Page.MULTIPLAYER); break;
            case LOBBY: leaveLobby(); break;
        }
    }

    // ------------------------------------------------------------------ actions

    private void startCampaign() {
        World w = new World(GameSetup.campaign(seed, difficulty));
        GalaxyGenerator.generate(w);
        launch(new GameScreen(game, w));
    }

    private void loadSave(boolean autosave) {
        try {
            World w = autosave ? SaveManager.loadAutosave() : SaveManager.load();
            if (w == null) {
                status = autosave ? "No autosave found." : "No saved game found.";
                return;
            }
            launch(new GameScreen(game, w));
        } catch (Exception e) {
            status = "Could not load " + (autosave ? "autosave" : "save") + ": " + e.getMessage();
        }
    }

    private String name() {
        String n = playerName.toString().trim();
        return n.isEmpty() ? "Commander" : n;
    }

    private void rememberName() {
        prefs.putString("playerName", name());
        prefs.putString("joinCode", joinCode.toString().trim());
        prefs.flush();
    }

    private void hostLobby() {
        rememberName();
        lobby = Lobby.create(name(), seed);
        lobby.pirates = difficulty;
        try {
            host = new NetHost(lobby);
            roomCodes(localAddresses());
            go(Page.LOBBY);
        } catch (Exception e) {
            host = null;
            go(Page.LOBBY);
            status = "LAN hosting unavailable (" + e.getMessage() + ") - you can still play against bots.";
        }
    }

    /** Joins by room code (or, as a fallback, a plain IP address). */
    private void join(String typed) {
        if (typed == null || typed.trim().isEmpty()) {
            status = "Enter the host's room code, or pick a game from the list.";
            return;
        }
        String addr = RoomCode.resolve(typed);
        if (addr == null) {
            status = "'" + typed.trim() + "' isn't a valid room code - check it with the host.";
            return;
        }
        rememberName();
        if (client != null) client.close();
        client = new NetClient(addr, name());
        String code = RoomCode.encode(addr);
        status = "Connecting to room " + (code != null ? code : addr) + "...";
    }

    /** Turns the host's addresses into room codes: private-network addresses first. */
    private void roomCodes(List<String> ips) {
        ips.sort((a, b) -> Integer.compare(privateRank(a), privateRank(b)));
        roomCode = null;
        StringBuilder more = new StringBuilder();
        for (String ip : ips) {
            String c = RoomCode.encode(ip);
            if (c == null) continue;
            if (roomCode == null) roomCode = c;
            else more.append(more.length() > 0 ? ", " : "").append(c);
        }
        otherCodes = more.length() > 0 ? more.toString() : null;
    }

    /** Home / office networks first (192.168.x, 10.x, 172.16-31.x), then everything else (VPNs, public). */
    private static int privateRank(String ip) {
        if (ip.startsWith("192.168.")) return 0;
        if (ip.startsWith("10.")) return 1;
        if (ip.startsWith("172.")) {
            String[] p = ip.split("\\.");
            try {
                int b = Integer.parseInt(p[1]);
                if (b >= 16 && b <= 31) return 1;
            } catch (Exception ignored) {
            }
        }
        return 2;
    }

    private void leaveLobby() {
        if (host != null) host.close();
        host = null;
        if (client != null) client.close();
        boolean wasClient = client != null;
        client = null;
        lobby = null;
        go(wasClient ? Page.JOIN : Page.MULTIPLAYER);
    }

    private void startLobbyGame() {
        String why = lobby.startBlocker();
        if (why != null) {
            status = why;
            return;
        }
        if (host == null || lobby.remoteHumans() == 0) { // nobody joined: an offline game against bots
            if (host != null) host.close();
            host = null;
            GameSetup setup = lobby.toSetup(null);
            setup.localSlot = 0;
            World w = new World(setup);
            GalaxyGenerator.generate(w);
            launch(new GameScreen(game, w));
            return;
        }
        GameSetup setup = host.start();
        World w = new World(setup);
        GalaxyGenerator.generate(w);
        NetHost h = host;
        host = null; // the game screen owns it now
        launch(new GameScreen(game, w, Lockstep.host(h)));
    }

    /** Client: the host started the game. */
    private void startAsClient(GameSetup setup) {
        World w = new World(setup);
        GalaxyGenerator.generate(w);
        NetClient c = client;
        client = null;
        launch(new GameScreen(game, w, Lockstep.client(c)));
    }

    private void launch(GameScreen s) {
        if (listener != null) listener.stop();
        listener = null;
        game.setScreen(s);
        dispose();
    }

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ per-frame logic

    private void updateNet() {
        if (host != null) host.pollLobby();
        if (client != null) {
            client.pollLobby();
            if (client.started != null) {
                startAsClient(client.started);
                return;
            }
            if (client.error != null) {
                String why = client.error;
                client.close();
                client = null;
                if (page == Page.LOBBY) go(Page.JOIN);
                status = why;
            } else if (client.lobby != null && page != Page.LOBBY) {
                go(Page.LOBBY);
            } else if (client.isClosed() && client.conn != null) {
                client = null;
                go(Page.JOIN);
                status = "Lost the connection to the host.";
            }
        }
    }

    // ------------------------------------------------------------------ layout

    private void build(float W, float H) {
        widgets.clear();
        switch (page) {
            case MAIN: buildMain(W, H); break;
            case CAMPAIGN: buildCampaign(W, H); break;
            case MULTIPLAYER: buildMultiplayer(W, H); break;
            case JOIN: buildJoin(W, H); break;
            case LOBBY: buildLobby(W, H); break;
        }
        if (builtFor != page) {
            builtFor = page;
            focus = 0;
        }
    }

    private void buildMain(float W, float H) {
        float bw = 380, bh = 52, x = (W - bw) / 2, y = H * 0.52f;
        button(x, y, bw, bh, "Campaign", () -> go(Page.CAMPAIGN));
        button(x, y -= 64, bw, bh, "Multiplayer", () -> go(Page.MULTIPLAYER));
        button(x, y -= 64, bw, bh, "Load Game", () -> loadSave(false)).enabled = SaveManager.exists();
        button(x, y -= 64, bw, bh, "Load Autosave", () -> loadSave(true)).enabled = SaveManager.autosaveExists();
        button(x, y -= 64, bw, bh, "Quit", Gdx.app::exit);
    }

    private void buildCampaign(float W, float H) {
        float bw = 560, bh = 48, x = (W - bw) / 2, y = H * 0.56f;
        label(x, y + 70, bw, 24, "CAMPAIGN  -  The Hive", TITLE, 1.6f);
        label(x - 120, y + 34, bw + 240, 20, "No rival empires. 91 hex systems joined by jump gates; pirate strongholds raid you forever.", DIM, 1f);
        label(x - 120, y + 14, bw + 240, 20, "Conquer hex by hex and raise the Ascension Gate.", DIM, 1f);
        select(x, y -= 50, bw, bh, "Difficulty", difficulty.title, d -> difficulty = d > 0 ? difficulty.harder() : difficulty.easier())
                .color = difficultyColor(difficulty);
        select(x, y -= 60, bw, bh, "Galaxy seed", String.valueOf(seed), d -> seed = new Random().nextInt(999999));
        button(x, y -= 76, bw, bh, "Start Campaign", this::startCampaign).color = GOOD;
        button(x, y -= 60, bw, bh, "Back", () -> go(Page.MAIN));
    }

    private void buildMultiplayer(float W, float H) {
        float bw = 560, bh = 48, x = (W - bw) / 2, y = H * 0.56f;
        label(x, y + 70, bw, 24, "MULTIPLAYER  -  LAN", TITLE, 1.6f);
        label(x - 120, y + 34, bw + 240, 20, "Up to 4 empires on the classic galaxy: friends on your network, bots, teams.", DIM, 1f);
        label(x - 120, y + 14, bw + 240, 20, "To play alone against bots, host a game, add bots and start - nobody needs to join.", DIM, 1f);
        text(x, y -= 50, bw, bh, "Your name", playerName, 20);
        button(x, y -= 72, bw, bh, "Host Game", this::hostLobby);
        button(x, y -= 60, bw, bh, "Join LAN Game", () -> {
            rememberName();
            go(Page.JOIN);
        });
        button(x, y -= 60, bw, bh, "Back", () -> go(Page.MAIN));
    }

    private void buildJoin(float W, float H) {
        float bw = 620, bh = 44, x = (W - bw) / 2, y = H * 0.7f;
        label(x, y + 40, bw, 24, "JOIN A LAN GAME", TITLE, 1.6f);
        List<LanDiscovery.Found> games = listener != null ? listener.games() : new ArrayList<>();
        if (games.isEmpty()) {
            label(x, y - 10, bw, 20, client != null ? "" : "Searching the network for games...", DIM, 1.05f);
            y -= 40;
        }
        for (LanDiscovery.Found g : games) {
            y -= 52;
            final String addr = g.address;
            String code = RoomCode.encode(addr);
            button(x, y, bw, bh, g.name + "    " + g.players + "/4    room " + (code != null ? code : addr), () -> join(addr));
        }
        if (listener != null && listener.error != null) label(x - 100, y - 30, bw + 200, 20, listener.error, WARN, 0.95f);
        y = Math.min(y, H * 0.42f);
        text(x, y -= 70, bw, bh, "Room code", joinCode, 40);
        label(x, y - 26, bw, 18, "Ask the host for their room code (for example K7Q-4MXV). The host's IP address works too.", DIM, 0.9f);
        Widget c = button(x, y -= 66, bw, bh, client != null ? "Connecting..." : "Connect", () -> join(joinCode.toString()));
        c.enabled = client == null;
        button(x, y -= 56, bw, bh, "Back", () -> {
            if (client != null) client.close();
            client = null;
            go(Page.MULTIPLAYER);
        });
    }

    private boolean isHostLobby() {
        return lobby != null && client == null;
    }

    private Lobby currentLobby() {
        return client != null ? client.lobby : lobby;
    }

    private void buildLobby(float W, float H) {
        Lobby l = currentLobby();
        if (l == null) {
            label(0, H * 0.5f, W, 24, "Waiting for the host...", DIM, 1.3f);
            button((W - 300) / 2, H * 0.5f - 80, 300, 44, "Leave", this::leaveLobby);
            return;
        }
        boolean hosting = isHostLobby();
        float tw = Math.min(1100, W - 80), x0 = (W - tw) / 2, y = H * 0.78f;
        label(x0, y + 30, tw, 24, hosting ? "GAME LOBBY  -  you are hosting" : "GAME LOBBY", TITLE, 1.6f);
        String sub = hosting
                ? (host != null ? (roomCode != null ? "ROOM CODE  " + roomCode + "   -   friends enter it under Multiplayer > Join LAN Game"
                        + (otherCodes != null ? "   (other networks: " + otherCodes + ")" : "")
                        : "No network found: friends can't join, but you can play against bots") : "Offline: bots only")
                : "Waiting for the host to start. You can pick your team and color.";
        label(x0, y, tw, 20, sub, DIM, 1f);

        // column layout: seat | name | difficulty | team | color
        float rowH = 46, gap = 8;
        float cSeat = 200, cName = 270, cDiff = 220, cTeam = 170, cColor = tw - cSeat - cName - cDiff - cTeam - gap * 4;
        y -= 40;
        for (int i = 0; i < l.entries.length; i++) {
            final int seat = i;
            Lobby.Entry e = l.entries[i];
            y -= rowH + 10;
            float x = x0;
            boolean filled = e.seat == Lobby.Seat.HUMAN || e.seat == Lobby.Seat.BOT;
            boolean mine = client != null ? seat == client.seat : seat == 0;
            boolean remoteHuman = e.seat == Lobby.Seat.HUMAN && (hosting ? e.conn != null : client.remote[seat]);

            // seat type
            String seatText = e.seat == Lobby.Seat.HUMAN ? (mine ? "You" : "Player") : e.seat == Lobby.Seat.BOT ? "Bot"
                    : e.seat == Lobby.Seat.OPEN ? "Open" : "Closed";
            if (hosting && seat != 0) {
                Widget s = select(x, y, cSeat, rowH, "", seatText, d -> cycleSeat(seat, d));
                s.color = e.seat == Lobby.Seat.BOT ? new Color(1f, 0.8f, 0.5f, 1f) : remoteHuman ? GOOD : DIM;
            } else label(x, y, cSeat, rowH, seatText, mine ? GOOD : Color.WHITE, 1.2f);
            x += cSeat + gap;

            // name
            label(x, y, cName, rowH, filled ? e.name : (e.seat == Lobby.Seat.OPEN ? "waiting for a player..." : "-"),
                    filled ? GameSetup.COLORS[Math.floorMod(e.color, GameSetup.COLORS.length)] : DIM, 1.15f);
            x += cName + gap;
            if (!filled) continue;

            // bot difficulty
            if (e.seat == Lobby.Seat.BOT) {
                if (hosting) select(x, y, cDiff, rowH, "", e.difficulty.title, d -> {
                    Difficulty[] v = Difficulty.values();
                    e.difficulty = v[Math.floorMod(e.difficulty.ordinal() + d, v.length)];
                    lobbyChanged();
                }).color = difficultyColor(e.difficulty);
                else label(x, y, cDiff, rowH, e.difficulty.title, difficultyColor(e.difficulty), 1.15f);
            }
            x += cDiff + gap;

            // team and color: the host sets everyone's; a client sets its own
            boolean editable = hosting || mine;
            String team = e.team == 0 ? "No team" : "Team " + e.team;
            if (editable) select(x, y, cTeam, rowH, "", team, d -> {
                e.team = Math.floorMod(e.team + d, 5);
                prefsChanged(seat);
            });
            else label(x, y, cTeam, rowH, team, Color.WHITE, 1.1f);
            x += cTeam + gap;
            String col = GameSetup.COLOR_NAMES[Math.floorMod(e.color, GameSetup.COLORS.length)];
            if (editable) select(x, y, cColor, rowH, "", col, d -> {
                e.color = Math.floorMod(e.color + d, GameSetup.COLORS.length);
                prefsChanged(seat);
            }).color = GameSetup.COLORS[Math.floorMod(e.color, GameSetup.COLORS.length)];
            else label(x, y, cColor, rowH, col, GameSetup.COLORS[Math.floorMod(e.color, GameSetup.COLORS.length)], 1.1f);
        }

        // game settings
        y -= 74;
        float half = (tw - gap) / 2;
        if (hosting) {
            select(x0, y, half, 46, "Seed", String.valueOf(l.seed), d -> {
                l.seed = new Random().nextInt(999999);
                lobbyChanged();
            });
            select(x0 + half + gap, y, half, 46, "Pirate threat", l.pirates.title, d -> {
                Difficulty[] v = Difficulty.values();
                l.pirates = v[Math.floorMod(l.pirates.ordinal() + d, v.length)];
                lobbyChanged();
            }).color = difficultyColor(l.pirates);
        } else {
            label(x0, y, half, 46, "Seed  " + l.seed, Color.WHITE, 1.15f);
            label(x0 + half + gap, y, half, 46, "Pirate threat  " + l.pirates.title, difficultyColor(l.pirates), 1.15f);
        }
        label(x0, y - 34, tw, 20, "Bots wait 8 minutes before they move. Bot ship caps: Easy 64, Normal 120, Hard 200, Insane unlimited.", DIM, 0.95f);

        y -= 100;
        if (hosting) {
            String why = l.startBlocker();
            Widget s = button(x0, y, half, 50, why == null ? (host != null && l.remoteHumans() > 0 ? "Start LAN Game" : "Start Game") : why,
                    this::startLobbyGame);
            s.enabled = why == null;
            s.color = GOOD;
        }
        button(hosting ? x0 + half + gap : x0 + half / 2, y, half, 50, "Leave Lobby", this::leaveLobby);
    }

    private void cycleSeat(int seat, int dir) {
        Lobby.Entry e = lobby.entries[seat];
        if (e.seat == Lobby.Seat.HUMAN && e.conn != null) { // a joined player: the host can only remove them
            host.kick(seat);
            e.seat = Lobby.Seat.OPEN;
            e.name = "";
            e.team = 0;
            lobbyChanged();
            return;
        }
        Lobby.Seat[] order = {Lobby.Seat.OPEN, Lobby.Seat.BOT, Lobby.Seat.CLOSED};
        int idx = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == e.seat) idx = i;
        e.seat = order[Math.floorMod(idx + dir, order.length)];
        e.name = e.seat == Lobby.Seat.BOT ? lobby.botName(seat) : "";
        if (e.seat != Lobby.Seat.BOT) e.team = 0;
        lobbyChanged();
    }

    private void lobbyChanged() {
        if (host != null) host.broadcastLobby();
    }

    private void prefsChanged(int seat) {
        if (client != null) {
            Lobby.Entry e = client.lobby.entries[seat];
            client.sendPrefs(e.team, e.color);
        } else lobbyChanged();
    }

    private static Color difficultyColor(Difficulty d) {
        switch (d) {
            case EASY: return new Color(0.5f, 1f, 0.6f, 1f);
            case HARD: return new Color(1f, 0.6f, 0.4f, 1f);
            case INSANE: return new Color(1f, 0.35f, 0.55f, 1f);
            default: return Color.WHITE;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(float delta) {
        t += delta;
        updateNet();
        if (game.getScreen() != this) return; // a game started this frame
        float W = Gdx.graphics.getWidth(), H = Gdx.graphics.getHeight();
        build(W, H);
        Widget f = focused();

        ScreenUtils.clear(0.01f, 0.01f, 0.035f, 1f);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer sr = game.shapes;
        sr.setProjectionMatrix(cam.combined);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        backdrop(sr, W, H);
        for (Widget w : widgets) {
            if (w.kind == Kind.LABEL) continue;
            boolean hot = w == f;
            sr.setColor(!w.enabled ? BTN_OFF : hot ? BTN_HOT : BTN);
            sr.rect(w.x, w.y, w.w, w.h);
            if (w.kind == Kind.TEXT) {
                sr.setColor(0.02f, 0.04f, 0.09f, 1f);
                sr.rect(w.x + w.w * 0.36f, w.y + 7, w.w * 0.62f, w.h - 14);
            }
            // edge accent on the left, brighter when focused
            sr.setColor(hot ? EDGE : new Color(EDGE.r, EDGE.g, EDGE.b, w.enabled ? 0.35f : 0.1f));
            sr.rect(w.x, w.y, 4, w.h);
            if (hot) {
                sr.rect(w.x, w.y, w.w, 2);
                sr.rect(w.x, w.y + w.h - 2, w.w, 2);
            }
        }
        sr.end();

        BitmapFont font = game.font;
        game.batch.setProjectionMatrix(cam.combined);
        game.batch.begin();
        if (page == Page.MAIN) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(t * 1.6f);
            font.getData().setScale(3.4f);
            font.setColor(TITLE.r * pulse, TITLE.g * pulse, TITLE.b, 1f);
            font.draw(game.batch, "NEBULA DOMINION", 0, H * 0.78f, W, Align.center, false);
            font.getData().setScale(1.2f);
            font.setColor(DIM);
            font.draw(game.batch, "An endless space RTS", 0, H * 0.78f - 64, W, Align.center, false);
        }
        for (Widget w : widgets) drawWidget(font, w, w == f);
        font.getData().setScale(1.05f);
        font.setColor(WARN);
        if (!status.isEmpty()) font.draw(game.batch, status, 0, 70, W, Align.center, true);
        font.getData().setScale(0.9f);
        font.setColor(0.45f, 0.5f, 0.6f, 1f);
        font.draw(game.batch, "Mouse or arrows / Enter  -  Left/Right change a setting  -  Esc back", 0, 30, W, Align.center, false);
        font.getData().setScale(1f);
        game.batch.end();
    }

    private void drawWidget(BitmapFont font, Widget w, boolean hot) {
        float cy = w.y + w.h / 2;
        switch (w.kind) {
            case LABEL: {
                font.getData().setScale(w.scale);
                font.setColor(w.color != null ? w.color : Color.WHITE);
                layout.setText(font, w.label);
                font.draw(game.batch, w.label, w.x, cy + layout.height / 2, w.w, Align.center, false);
                break;
            }
            case BUTTON: {
                font.getData().setScale(w.scale);
                Color c = !w.enabled ? Color.GRAY : w.color != null ? w.color : Color.WHITE;
                font.setColor(c);
                layout.setText(font, w.label);
                font.draw(game.batch, w.label, w.x, cy + layout.height / 2, w.w, Align.center, false);
                break;
            }
            case SELECT: {
                font.getData().setScale(w.scale);
                boolean hasLabel = w.label != null && !w.label.isEmpty();
                if (hasLabel) {
                    font.setColor(DIM);
                    layout.setText(font, w.label);
                    font.draw(game.batch, w.label, w.x + 18, cy + layout.height / 2);
                }
                String v = "<   " + w.value + "   >";
                font.setColor(w.color != null ? w.color : Color.WHITE);
                layout.setText(font, v);
                float vx = hasLabel ? w.x + w.w * 0.36f : w.x;
                float vw = hasLabel ? w.w * 0.62f : w.w;
                font.draw(game.batch, v, vx, cy + layout.height / 2, vw, Align.center, false);
                break;
            }
            case TEXT: {
                font.getData().setScale(w.scale);
                font.setColor(DIM);
                layout.setText(font, w.label);
                font.draw(game.batch, w.label, w.x + 18, cy + layout.height / 2);
                boolean caret = hot && ((int) (t * 2.2f)) % 2 == 0;
                String s = w.text + (caret ? "_" : "");
                font.setColor(Color.WHITE);
                layout.setText(font, s.isEmpty() ? " " : s);
                font.draw(game.batch, s, w.x + w.w * 0.36f + 12, cy + layout.height / 2);
                break;
            }
        }
    }

    private void backdrop(ShapeRenderer sr, float W, float H) {
        for (int i = 0; i < sx.length; i++) {
            float x = ((sx[i] * W - t * 6 * sz[i]) % W + W) % W;
            float b = 0.3f + 0.5f * (sz[i] / 2.5f);
            sr.setColor(b, b, b + 0.1f, 1f);
            sr.rect(x, sy[i] * H, sz[i], sz[i]);
        }
        // decorative planet and sun
        sr.setColor(0.12f, 0.25f, 0.45f, 1f);
        sr.circle(W * 0.86f, H * 0.22f, 160, 64);
        sr.setColor(0.2f, 0.4f, 0.65f, 1f);
        sr.circle(W * 0.86f - 25, H * 0.22f + 25, 135, 64);
        sr.setColor(1f, 0.85f, 0.45f, 0.08f);
        sr.circle(W * 0.1f, H * 0.86f, 140, 48);
        sr.setColor(1f, 0.85f, 0.45f, 1f);
        sr.circle(W * 0.1f, H * 0.86f, 50, 48);
        // content panel behind the widgets
        if (page != Page.MAIN && !widgets.isEmpty()) {
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (Widget w : widgets) {
                x0 = Math.min(x0, w.x);
                y0 = Math.min(y0, w.y);
                x1 = Math.max(x1, w.x + w.w);
                y1 = Math.max(y1, w.y + w.h);
            }
            sr.setColor(PANEL);
            sr.rect(x0 - 30, y0 - 26, x1 - x0 + 60, y1 - y0 + 52);
        }
    }

    @Override
    public void resize(int width, int height) {
        cam.setToOrtho(false, width, height);
    }

    @Override
    public void hide() {
        if (listener != null) listener.stop();
        listener = null;
    }
}

package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.research.RareItem;
import spacerts.research.Tech;
import spacerts.systems.StationRally;
import spacerts.units.ShipClass;
import spacerts.units.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Warcraft III cheat codes, typed into the chat (single player only). Codes aren't case-sensitive and
 * spaces are ignored. Cheats stay locked until the activation code is entered; "cheatlist" prints them.
 */
public final class Cheats {
    private Cheats() {}

    /** Unlocks (and, typed again, locks) the cheats. */
    public static final String ACTIVATION = "thereisnocowlevel";
    public static final Color LIST_C = new Color(1f, 0.82f, 0.35f, 1f);

    public enum Code {
        WHOSYOURDADDY("WhosYourDaddy", "", "god mode: your units can't be hurt and kill in one hit", true),
        ISEEDEADPEOPLE("ISeeDeadPeople", "", "reveals the whole map, no fog of war", true),
        SHARPANDSHINY("SharpAndShiny", "", "every tech researched, Mothership and Ship Stations to tier III", false),
        WARPTEN("WarpTen", "", "ships, buildings and upgrades build 10x faster", true),
        WHOISJOHNGALT("WhoIsJohnGalt", "", "research runs 10x faster", true),
        SYNERGY("Synergy", "", "no tech, tier or prerequisite requirements", true),
        KEYSERSOZE("KeyserSoze", " [n]", "+n energy (default 500)", false),
        LEAFITTOME("LeafItToMe", " [n]", "+n metal (default 500)", false),
        GREEDISGOOD("GreedIsGood", " [n]", "+n energy and metal and some of every rare item (default 500)", false),
        POINTBREAK("PointBreak", "", "no housing limit", true),
        THEREISNOSPOON("ThereIsNoSpoon", "", "skills never need to recharge", true),
        THEDUDEABIDES("TheDudeAbides", "", "resets every skill cooldown now", false),
        IOCAINEPOWDER("IocainePowder", "", "wrecks rot away in seconds", true),
        STRENGTHANDHONOR("StrengthAndHonor", "", "losing the Mothership doesn't end the game", true),
        ITVEXESME("ItVexesMe", "", "victory is disabled", true),
        MOTHERLAND("Motherland", " [hex]", "jumps your Mothership, stations and fleet to a hex (name or number; empty = centre)", false),
        SOMEBODYSETUPUSTHEBOMB("SomebodySetUpUsTheBomb", "", "instant defeat", false),
        ALLYOURBASEAREBELONGTOUS("AllYourBaseAreBelongToUs", "", "instant victory", false),
        RISEANDSHINE("RiseAndShine", "", "pushes the next pirate raid back 5 minutes", false),
        LIGHTSOUT("LightsOut", "", "the next pirate raid comes now", false),
        DAYLIGHTSAVINGS("DayLightSavings", " [s]", "with s: next raid in s seconds; without: freeze / unfreeze the raid timer", false),
        TENTHLEVELTAURENCHIEFTAIN("TenthLevelTaurenChieftain", "", "a light show for your Mothership", false);

        public final String title, args, desc, key;
        public final boolean toggle;

        Code(String title, String args, String desc, boolean toggle) {
            this.title = title;
            this.args = args;
            this.desc = desc;
            this.toggle = toggle;
            this.key = title.toLowerCase(Locale.ROOT);
        }
    }

    /** Lower case with all whitespace removed. */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** "cheatlist", "/cheats" or "/help cheats". */
    public static boolean isListRequest(String line) {
        String n = normalize(line);
        return n.equals("cheatlist") || n.equals("/cheats") || n.equals("/helpcheats");
    }

    /** The code this line starts with, or null. */
    public static Code match(String line) {
        String n = normalize(line);
        Code best = null;
        for (Code c : Code.values())
            if (n.startsWith(c.key) && (best == null || c.key.length() > best.key.length())) best = c;
        if (best == null) return null;
        String rest = n.substring(best.key.length());
        if (rest.isEmpty() || best == Code.MOTHERLAND) return best;
        if (!best.args.isEmpty() && rest.matches("\\d{1,9}")) return best;
        return null; // e.g. "warptenx" is just chat
    }

    /** True when the line is the activation code or a cheat code (so it is never posted as chat). */
    public static boolean isCheat(String line) {
        return normalize(line).equals(ACTIVATION) || match(line) != null;
    }

    /** True when cheats are allowed: no other human in the game. */
    public static boolean allowed(World w) {
        int humans = 0;
        for (Faction f : w.factions) if (f.isPlayer()) humans++;
        return humans <= 1;
    }

    /** Prints the cheat list as local chat lines for f (shown only on f's machine). */
    public static void list(World w, Faction f) {
        if (f != w.player) return;
        List<String> lines = new ArrayList<>();
        if (!f.cheatsUnlocked) lines.add("Locked - type the activation code first. Codes:");
        else lines.add("Unlocked. Type a code in chat (any case, spaces ignored). Codes:");
        for (Code c : Code.values())
            lines.add(c.title + c.args + " - " + c.desc + (c.toggle ? (on(f, w, c) ? " (ON)" : " (off)") : ""));
        if (!allowed(w)) lines.add("Cheats only work when no other human is in the game.");
        for (String s : lines) w.messages.add(new Message("[Cheats] " + s, w.time, LIST_C, true));
    }

    private static boolean on(Faction f, World w, Code c) {
        switch (c) {
            case WHOSYOURDADDY: return f.godMode;
            case ISEEDEADPEOPLE: return f.noFog;
            case WARPTEN: return f.warpTen;
            case WHOISJOHNGALT: return f.fastResearch;
            case SYNERGY: return f.synergy;
            case POINTBREAK: return f.noHousing;
            case THEREISNOSPOON: return f.noCooldowns;
            case IOCAINEPOWDER: return f.fastDecay;
            case STRENGTHANDHONOR: return f.noDefeat;
            case ITVEXESME: return w.noVictory;
            default: return false;
        }
    }

    /**
     * Applies a cheat line for f. Returns the message to show f, or null when the line is not a cheat.
     * Runs inside the command stream, so it is deterministic.
     */
    public static String apply(World w, Faction f, String line) {
        String n = normalize(line);
        if (n.equals(ACTIVATION)) {
            if (f.cheatsUnlocked) {
                f.cheatsUnlocked = false;
                f.godMode = f.noFog = f.warpTen = f.fastResearch = f.synergy = f.noHousing = f.noCooldowns = f.fastDecay = f.noDefeat = false;
                w.noVictory = w.raidsFrozen = false;
                return "Cheats locked. Every cheat is off again.";
            }
            f.cheatsUnlocked = true;
            return "Cheats unlocked. Type cheatlist for the codes.";
        }
        Code c = match(line);
        if (c == null) return null;
        if (!f.cheatsUnlocked) return "Cheats are locked.";
        f.cheatsUsed = true;
        String rest = n.substring(c.key.length());
        int amount = 500;
        if (!rest.isEmpty() && rest.matches("\\d{1,9}")) amount = Integer.parseInt(rest);
        switch (c) {
            case WHOSYOURDADDY: return toggled(c, f.godMode = !f.godMode);
            case ISEEDEADPEOPLE: return toggled(c, f.noFog = !f.noFog);
            case WARPTEN: return toggled(c, f.warpTen = !f.warpTen);
            case WHOISJOHNGALT: return toggled(c, f.fastResearch = !f.fastResearch);
            case SYNERGY: return toggled(c, f.synergy = !f.synergy);
            case POINTBREAK: return toggled(c, f.noHousing = !f.noHousing);
            case THEREISNOSPOON:
                f.noCooldowns = !f.noCooldowns;
                if (f.noCooldowns) resetCooldowns(w, f);
                return toggled(c, f.noCooldowns);
            case IOCAINEPOWDER: return toggled(c, f.fastDecay = !f.fastDecay);
            case STRENGTHANDHONOR: return toggled(c, f.noDefeat = !f.noDefeat);
            case ITVEXESME: return toggled(c, w.noVictory = !w.noVictory);
            case KEYSERSOZE:
                f.energy += amount;
                return enabled(c) + " +" + amount + " energy.";
            case LEAFITTOME:
                f.metal += amount;
                return enabled(c) + " +" + amount + " metal.";
            case GREEDISGOOD: {
                f.energy += amount;
                f.metal += amount;
                int r = Math.max(1, amount / 100);
                for (RareItem it : RareItem.values()) {
                    f.rare[it.ordinal()] += r;
                    f.found[it.ordinal()] = true;
                }
                return enabled(c) + " +" + amount + " energy and metal, +" + r + " of every rare item.";
            }
            case SHARPANDSHINY: {
                for (Tech t : Tech.values()) if (!f.has(t)) w.production.complete(f, t, false);
                f.researching = null;
                f.researchProgress = 0;
                f.researching2 = null;
                f.researchProgress2 = 0;
                for (Unit u : w.units)
                    if (!u.dead && u.owner == f && (u.cls.isFlagship() || u.cls == ShipClass.FACTORY) && u.tier < 3) {
                        float hp = u.hp / u.maxHp(), sh = u.maxShield() > 0 ? u.shield / u.maxShield() : 0;
                        u.tier = 3;
                        u.hp = u.maxHp() * hp;
                        u.shield = u.maxShield() * sh;
                    }
                return enabled(c) + " Every tech researched; Mothership and Ship Stations at tier III.";
            }
            case THEDUDEABIDES:
                resetCooldowns(w, f);
                return enabled(c) + " Every skill is ready.";
            case MOTHERLAND: return motherland(w, f, rest);
            case SOMEBODYSETUPUSTHEBOMB:
                w.announce(f.name + " set up us the bomb.", World.ALERT);
                w.eliminate(f);
                return enabled(c);
            case ALLYOURBASEAREBELONGTOUS: return allYourBase(w, f);
            case RISEANDSHINE:
                w.threats.nextWave += 300f;
                return enabled(c) + " The next pirate raid is 5 minutes later.";
            case LIGHTSOUT:
                w.threats.nextWave = w.time;
                return enabled(c) + " The pirates are coming NOW.";
            case DAYLIGHTSAVINGS:
                if (!rest.isEmpty()) {
                    w.threats.nextWave = w.time + amount;
                    return enabled(c) + " Next pirate raid in " + amount + " s.";
                }
                w.raidsFrozen = !w.raidsFrozen;
                return toggled(c, w.raidsFrozen) + (w.raidsFrozen ? " The raid timer is frozen." : " The raid timer runs again.");
            case TENTHLEVELTAURENCHIEFTAIN: {
                Unit ms = f.mothership;
                if (ms != null && !ms.dead) {
                    Color[] cs = {Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA};
                    for (int i = 0; i < 14; i++) w.ring(ms.x, ms.y, ms.radius * (1.5f + i * 0.6f), cs[i % cs.length], 0.6f + i * 0.15f);
                }
                w.announce("*** POWER OF THE HORDE *** " + f.name + "'s Mothership rocks out.", Color.ORANGE);
                return enabled(c);
            }
            default:
                return null;
        }
    }

    private static String enabled(Code c) {
        return "Cheat enabled: " + c.title + ".";
    }

    private static String toggled(Code c, boolean on) {
        return "Cheat enabled: " + c.title + " " + (on ? "ON" : "OFF") + " - " + c.desc + ".";
    }

    private static void resetCooldowns(World w, Faction f) {
        for (Unit u : w.units)
            if (!u.dead && u.owner == f) java.util.Arrays.fill(u.abilityCds, 0f);
    }

    /** Motherland [hex]: teleports the Mothership, stations and warships (with their drones) to a hex. */
    private static String motherland(World w, Faction f, String rest) {
        float tx, ty;
        String where;
        if (w.galaxy.hive) {
            StarSystem dest = null;
            if (rest.isEmpty()) dest = w.galaxy.at(0, 0);
            else if (rest.matches("\\d{1,4}")) {
                int i = Integer.parseInt(rest);
                if (i >= 0 && i < w.galaxy.systems.size()) dest = w.galaxy.systems.get(i);
            } else
                for (StarSystem s : w.galaxy.systems) if (normalize(s.name).startsWith(rest)) { dest = s; break; }
            if (dest == null) return "Motherland: no hex called \"" + rest + "\".";
            tx = dest.cx;
            ty = dest.cy;
            where = dest.name;
        } else {
            tx = ty = Galaxy.CLASSIC_SIZE / 2;
            where = "the centre of the map";
        }
        List<Unit> movers = new ArrayList<>();
        for (Unit u : w.units)
            if (!u.dead && u.owner == f && (u.cls.isFlagship() || u.cls.isStation() || u.cls.isWarship())) movers.add(u);
        if (movers.isEmpty()) return "Motherland: nothing to move.";
        movers.sort((a, b) -> a.cls.isFlagship() ? -1 : b.cls.isFlagship() ? 1 : Integer.compare(a.id, b.id));
        for (int i = 0; i < movers.size(); i++) {
            Unit u = movers.get(i);
            float ox = 0, oy = 0;
            if (i > 0) {
                float[] o = StationRally.slot(i - 1);
                ox = o[0];
                oy = o[1];
            }
            place(w, u, tx + ox, ty + oy);
            if (u.drones != null)
                for (Unit d : u.drones) if (!d.dead) place(w, d, u.x + (d.id % 7) * 6 - 18, u.y + (d.id % 5) * 6 - 12);
        }
        w.ring(tx, ty, 900, f.color, 1.5f);
        return enabled(Code.MOTHERLAND) + " Your fleet jumped to " + where + ".";
    }

    private static void place(World w, Unit u, float x, float y) {
        u.x = w.clampX(x, y);
        u.y = w.clampY(x, y);
        u.vx = u.vy = 0;
        u.warping = false;
        u.warpCharge = 0;
        u.lastJump = w.time;
        if (u.cls != ShipClass.DRONE) u.idle();
    }

    /** Instant victory: every hostile empire is eliminated; in Campaign the pirates fall and the galaxy ascends. */
    private static String allYourBase(World w, Faction f) {
        if (w.piratesOnly()) {
            for (Unit u : w.units) if (!u.dead && u.owner.kind == Faction.Kind.PIRATES) u.kill(true);
            if (!w.ascended) {
                w.ascended = true;
                w.announce("ALL YOUR BASE ARE BELONG TO US. The pirate clans are broken - the galaxy is yours.", World.GOOD);
            }
        } else {
            for (Faction o : w.factions)
                if (o.isMajor() && !o.eliminated && f.hostileTo(o)) w.eliminate(o);
        }
        return enabled(Code.ALLYOURBASEAREBELONGTOUS);
    }
}

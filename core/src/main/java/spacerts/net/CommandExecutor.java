package spacerts.net;

import com.badlogic.gdx.graphics.Color;
import spacerts.research.Tech;
import spacerts.units.Ability;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies player {@link Command}s to the simulation. Runs on every machine of a LAN game at the same tick, so it
 * must only depend on the world and the command (never on which empire is local). Messages go through
 * World.notify, which shows them only on the issuing player's machine; cosmetic effects are drawn only there too.
 */
public final class CommandExecutor {
    private CommandExecutor() {}

    private static final Color MOVE_C = new Color(0.4f, 1f, 0.5f, 1f);

    /** Applies c. PAUSE and SPEED are game-flow commands handled by the caller and ignored here. */
    public static void apply(World w, Command c) {
        if (c.faction < 0 || c.faction >= w.factions.size()) return;
        Faction f = w.factions.get(c.faction);
        if (!f.isMajor()) return;
        if (c.type == Command.Type.LEAVE) {
            w.handToBot(f);
            return;
        }
        if (c.type == Command.Type.CHAT) { // spectators (eliminated players) can still talk
            chat(w, f, c);
            return;
        }
        if (f.eliminated || !f.isPlayer()) return; // bots ignore stray commands from a player who left
        switch (c.type) {
            case ORDER: order(w, f, c); break;
            case STOP:
                for (Unit u : own(w, f, c.units))
                    if (!u.s.stationary) {
                        u.formation = 0;
                        u.idle();
                        u.clearAuto();
                    }
                break;
            case ENQUEUE: {
                Unit p = ownUnit(w, f, c.target);
                ShipClass cls = shipClass(c.name);
                if (p == null || p.queue == null || cls == null) break;
                String err = w.production.enqueue(p, cls);
                if (err != null) w.notify(f, err, World.ALERT);
                else if (c.arg == 1) w.notify(f, cls.s.name + " started.", World.INFO);
                break;
            }
            case CANCEL_QUEUE: {
                Unit p = ownUnit(w, f, c.target);
                if (p != null && p.queue != null && c.arg >= 0 && c.arg < p.queue.items.size()) w.production.cancel(p, c.arg);
                break;
            }
            case RESEARCH: {
                Tech t = tech(c.name);
                if (t == null) break;
                if (f.researchingNow(t)) { // clicking a tech in either lab cancels it
                    w.production.cancelResearch(f, t);
                    break;
                }
                if (f.has(t)) break;
                String err = w.production.startResearch(f, t);
                if (err != null) w.notify(f, err, World.ALERT);
                break;
            }
            case CANCEL_RESEARCH:
                if (f.researching != null) w.production.cancelResearch(f);
                else if (f.researching2 != null) w.production.cancelResearch(f, f.researching2);
                break;
            case BUILD: build(w, f, c); break;
            case UPGRADE_PLANET: upgradePlanet(w, f, c); break;
            case ABILITY: abilities(w, f, c); break;
            case RETREAT:
                for (Unit u : own(w, f, c.units)) if (u.cls.isWarship()) u.retreatOff = c.arg == 0;
                w.notify(f, c.arg == 1 ? "Auto-retreat ON: these ships pull back to repair below 25% hull."
                        : "Auto-retreat OFF: these ships fight to the end.", World.INFO);
                break;
            case SET_FLAG:
                f.hasFlag = true;
                f.flagX = w.clampX(c.x, c.y);
                f.flagY = w.clampY(c.x, c.y);
                if (f == w.player) w.ring(c.x, c.y, 80, f.color, 0.5f);
                w.notify(f, "Rally flag placed. New warships will gather here (Shift+F removes it).", World.GOOD);
                break;
            case CLEAR_FLAG:
                f.hasFlag = false;
                w.notify(f, "Rally flag removed.", World.INFO);
                break;
            case DESTROY: destroy(w, f, c); break;
            case SET_TACTIC:
                if (c.arg >= 0 && c.arg < spacerts.units.Tactic.values().length && f.tactics.contains(spacerts.units.Tactic.values()[c.arg])) {
                    f.activeTactic = spacerts.units.Tactic.values()[c.arg];
                    w.notify(f, "Active tactic: " + f.activeTactic.title + ".", World.INFO);
                }
                break;
            case TACTIC_ATTACK: tacticAttack(w, f, c); break;
            case SET_TARGETING:
                if (c.arg >= 0 && c.arg < spacerts.units.Targeting.values().length) {
                    spacerts.units.Targeting t = spacerts.units.Targeting.values()[c.arg];
                    int n = 0;
                    for (Unit u : own(w, f, c.units)) if (u.cls.isWarship() || u.armed()) { u.targeting = t; n++; }
                    if (n > 0) w.notify(f, "Targeting: " + t.title + " (" + n + (n == 1 ? " ship)." : " ships)."), World.INFO);
                }
                break;
            case CHEAT: {
                if (!spacerts.world.Cheats.allowed(w)) break; // never with another human in the game
                String msg = spacerts.world.Cheats.apply(w, f, c.name);
                if (msg != null) w.notify(f, msg, msg.startsWith("Cheats are locked") ? World.ALERT : World.GOOD);
                break;
            }
            case SURRENDER:
                if (f.mothership != null && !f.mothership.dead) {
                    w.announce(f.name + " surrendered.", World.ALERT);
                    w.eliminate(f); // always a defeat, even with StrengthAndHonor on
                }
                break;
            case RALLY_STATIONS:
                f.rallyStations = c.arg == 1;
                w.notify(f, f.rallyStations ? "Rally Stations ON: your stations form up around the Mothership and move with it."
                        : "Rally Stations OFF: stations hold position and crawl at half speed.", World.INFO);
                break;
            case AUTO_DEFENSE:
                f.autoDefense = c.arg == 1;
                w.notify(f, "Auto-defense " + (f.autoDefense ? "ON: idle ships defend your bases and harvesters flee raids."
                        : "OFF: your units only do what you tell them."), World.INFO);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ tactics

    /** True while f has a living Command Center (needed to start tactical attacks). */
    public static boolean hasCommandCenter(World w, Faction f) {
        for (Unit u : w.units) if (!u.dead && u.owner == f && u.cls == ShipClass.COMMAND) return true;
        return false;
    }

    /**
     * Tactical attack: the selected ships with a place in the active tactic form up, march and fight as one;
     * the rest of the selection (and everything, without a Command Center) simply attack-moves there.
     */
    private static void tacticAttack(World w, Faction f, Command c) {
        spacerts.units.Tactic t = f.activeTactic;
        boolean ok = t != null && hasCommandCenter(w, f);
        float x = w.clampX(c.x, c.y), y = w.clampY(c.x, c.y);
        Unit enemy = unit(w, c.target);
        if (enemy != null && f.hostileTo(enemy.owner)) {
            x = enemy.x;
            y = enemy.y;
        }
        List<Unit> members = new ArrayList<>(), others = new ArrayList<>();
        for (Unit u : own(w, f, c.units)) {
            if (u.s.stationary || u.cls.isBuilding() || u.s.harvester || u.cls == ShipClass.DEFENDER || u.cls == ShipClass.DRONE) continue;
            spacerts.systems.TacticsSystem.Role r = spacerts.systems.TacticsSystem.role(u.cls);
            if (ok && spacerts.systems.TacticsSystem.uses(t, r)) members.add(u);
            else if (u.cls.isWarship() || u.armed()) others.add(u);
        }
        if (members.size() < 2) { // nothing to form: everyone just attack-moves
            others.addAll(members);
            members.clear();
        }
        for (Unit u : others) {
            u.formation = 0;
            u.clearAuto();
            u.command(Order.ATTACK_MOVE, x, y);
        }
        if (members.isEmpty()) {
            if (t == null) w.notify(f, "No tactic selected - unlock one at a Command Center.", World.INFO);
            else if (!ok) w.notify(f, "Tactics need a Command Center - attack-moving instead.", World.INFO);
            return;
        }
        f.hasTacticTarget = true;
        f.tacticX = x;
        f.tacticY = y;
        w.tactics.start(f, t, members, x, y);
        w.notify(f, t.title + ": " + members.size() + " ships forming up" + (others.isEmpty() ? "." : "; " + others.size() + " others attack-move."), World.INFO);
    }

    // ------------------------------------------------------------------ destroy & chat

    /** Share of a unit's base price refunded when it is scuttled. */
    public static final float SCUTTLE_REFUND = 0.25f;
    /** Longest chat line. */
    public static final int CHAT_MAX = 120;

    /** Ships and structures the player built can be scuttled; colonies, turrets, outposts, drones and the Mothership can't. */
    public static boolean destroyable(Unit u) {
        return !u.dead && !u.s.internal && !u.cls.isFlagship();
    }

    /** {energy, metal} refunded for scuttling u (rare items: floor of 25% each, see scuttleRare). */
    public static int[] scuttleRefund(Unit u) {
        return new int[]{(int) (u.s.energy / (float) u.s.squad * SCUTTLE_REFUND), (int) (u.s.metal / (float) u.s.squad * SCUTTLE_REFUND)};
    }

    private static int[] scuttleRare(Unit u) {
        int[] r = new int[u.s.rare.length];
        for (int i = 0; i < r.length; i++) r[i] = (int) (u.s.rare[i] / (float) u.s.squad * SCUTTLE_REFUND);
        return r;
    }

    /** Scuttles the selected units: queues are refunded in full, the hulls for 25% of their base price. */
    private static void destroy(World w, Faction f, Command c) {
        int n = 0, e = 0, m = 0;
        for (Unit u : own(w, f, c.units)) {
            if (!destroyable(u)) continue;
            if (u.queue != null)
                for (int i = u.queue.items.size() - 1; i >= 0; i--) w.production.cancel(u, i);
            int[] back = scuttleRefund(u);
            f.refund(back[0], back[1], scuttleRare(u));
            e += back[0];
            m += back[1];
            n++;
            u.kill(true); // silent: no wreck to salvage on top, no Brood Swarm / skiffs
            w.blast(u.x, u.y, Math.max(u.radius * 2f, 24f), f.color);
        }
        if (n > 0) w.notify(f, "Scuttled " + n + (n == 1 ? " unit" : " units") + ": +" + e + " energy, +" + m + " metal.", World.INFO);
    }

    /** Printable ASCII only, trimmed and capped; null when nothing is left. */
    public static String cleanChat(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < CHAT_MAX; i++) {
            char ch = s.charAt(i);
            if (ch >= 32 && ch < 127) sb.append(ch);
        }
        String t = sb.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /** A chat line: everyone sees [All] lines; [Team] lines only reach the sender's allies. */
    private static void chat(World w, Faction f, Command c) {
        String text = cleanChat(c.name);
        if (text == null) return;
        boolean team = c.arg == 1;
        if (team && w.player.hostileTo(f)) return; // not for this machine's player
        w.messages.add(new spacerts.world.Message((team ? "[Team] " : "[All] ") + f.name + ": " + text, w.time, f.color, true));
    }

    // ------------------------------------------------------------------ lookups

    private static Map<Integer, Unit> index(World w) {
        Map<Integer, Unit> m = new HashMap<>();
        for (Unit u : w.units) if (!u.dead) m.put(u.id, u);
        return m;
    }

    /** f's living units among the ids, in command order. */
    static List<Unit> own(World w, Faction f, int[] ids) {
        List<Unit> out = new ArrayList<>();
        if (ids.length == 0) return out;
        Map<Integer, Unit> m = index(w);
        for (int id : ids) {
            Unit u = m.get(id);
            if (u != null && u.owner == f) out.add(u);
        }
        return out;
    }

    private static Unit unit(World w, int id) {
        if (id < 0) return null;
        for (Unit u : w.units) if (u.id == id && !u.dead) return u;
        return null;
    }

    private static Unit ownUnit(World w, Faction f, int id) {
        Unit u = unit(w, id);
        return u != null && u.owner == f ? u : null;
    }

    /** The living body at exactly this position (bodies never move). */
    public static Body bodyAt(World w, Command c) {
        if (!c.hasBody) return null;
        for (Body b : w.bodies) if (!b.dead && b.x == c.bodyX && b.y == c.bodyY) return b;
        return null;
    }

    private static ShipClass shipClass(String n) {
        if (n == null) return null;
        try {
            return ShipClass.valueOf(n);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Tech tech(String n) {
        if (n == null) return null;
        try {
            return Tech.valueOf(n);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ orders

    /** Right-click: the formation move / attack / harvest / colonize / survey logic of the original input code. */
    private static void order(World w, Faction f, Command c) {
        List<Unit> movers = new ArrayList<>();
        Unit enemy = unit(w, c.target);
        if (enemy != null && !f.hostileTo(enemy.owner)) enemy = null;
        boolean guarded = f.mothership != null && !f.mothership.dead;
        int heldStations = 0, heldDefenders = 0;
        boolean any = false;
        float wx = c.x, wy = c.y;
        for (Unit u : own(w, f, c.units)) {
            any = true;
            if (u.s.stationary || (u.queue != null && c.shift)) { // Shift+right-click on a producer: set its rally point
                if (u.queue != null) {
                    u.hasRally = true;
                    u.rallyX = wx;
                    u.rallyY = wy;
                }
                continue;
            }
            if (u.rallied) { heldStations++; continue; } // Rally Stations: the formation decides where it goes
            if (u.cls == ShipClass.DEFENDER && guarded && enemy == null) { heldDefenders++; continue; } // never leaves its orbit
            u.formation = 0; // a direct order takes the ship out of its tactic formation
            u.clearAuto(); // the player takes over from automation
            u.fleeTimer = 0;
            movers.add(u);
        }
        if (!any) return;
        if (heldStations > 0) w.notify(f, "Stations are rallied to the Mothership - press V to release them.", World.INFO);
        if (heldDefenders > 0) w.notify(f, "Defenders stay in orbit around the Mothership (right-click an enemy to pick their target).", World.INFO);
        Body body = bodyAt(w, c);
        boolean attackMove = c.arg == 1;

        int n = movers.size();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
        int rows = (n + cols - 1) / cols;
        float spacing = 34;
        for (Unit u : movers) spacing = Math.max(spacing, u.radius * 2.6f);

        for (int i = 0; i < n; i++) {
            Unit u = movers.get(i);
            if (enemy != null && u.armed()) {
                u.order = Order.ATTACK;
                u.target = enemy;
                u.body = null;

                continue;
            }
            if (body != null) {
                if (u.s.harvester && body.harvestable()) {
                    w.economy.assign(u, body);
                    continue;
                }
                if (u.s.colonizer && body.habitable()) {
                    u.command(Order.COLONIZE, body.x, body.y);
                    u.body = body;
                    continue;
                }
                if (u.s.surveyor && body.type != null && body.type.rare != null) {
                    u.command(Order.SURVEY, body.x, body.y);
                    u.body = body;
                    continue;
                }
            }
            int col = i % cols, row = i / cols;
            float ox = (col - (cols - 1) / 2f) * spacing, oy = ((rows - 1) / 2f - row) * spacing;
            u.command(attackMove ? Order.ATTACK_MOVE : Order.MOVE, wx + ox, wy + oy);
        }
        // the click ring is drawn by the input code right away, before the command reaches the simulation
    }

    private static void build(World w, Faction f, Command c) {
        Unit h = ownUnit(w, f, c.target);
        ShipClass cls = shipClass(c.name);
        if (h == null || cls == null || !h.s.harvester) return;
        String err = w.construction.order(h, cls, bodyAt(w, c), c.x, c.y);
        if (err != null) w.notify(f, err, World.ALERT);
    }

    private static void upgradePlanet(World w, Faction f, Command c) {
        ShipClass cls = shipClass(c.name);
        Body colony = bodyAt(w, c);
        if (cls == null || colony == null || !colony.colonized() || colony.colony.owner != f) return;
        String why = w.construction.baseBlocker(f, cls);
        if (why == null) why = f.missing(cls.s.energy, cls.s.metal, cls.s.rare);
        Unit h = null;
        if (why == null) {
            h = w.construction.nearestFreeBuilder(f, colony);
            if (h == null) why = "No free harvester to build the upgrade";
        }
        if (why == null) why = w.construction.order(h, cls, colony, colony.x, colony.y);
        if (why != null) {
            w.notify(f, why, World.ALERT);
            return;
        }
        w.notify(f, "A harvester is on its way to build a " + cls.s.name + " on " + colony.name + ".", World.INFO);
        if (f == w.player) w.ring(colony.x, colony.y, colony.radius * 2f, MOVE_C, 0.35f);
    }

    private static void abilities(World w, Faction f, Command c) {
        String firstErr = null;
        boolean anyOk = false;
        Set<ShipClass> used = new HashSet<>();
        Ability a = c.arg > 0 && c.arg < Ability.values().length ? Ability.values()[c.arg] : null;
        if (a == null) return;
        for (Unit u : own(w, f, c.units)) {
            if (!u.s.has(a)) continue;
            if (a == Ability.PING && used.contains(u.cls)) continue; // one Ping per keypress is enough
            String err = w.behavior.useAbility(u, a, c.x, c.y);
            if (err == null) {
                anyOk = true;
                used.add(u.cls);
            } else if (firstErr == null) firstErr = err;
        }
        if (!anyOk && firstErr != null) w.notify(f, firstErr, World.INFO);
    }
}

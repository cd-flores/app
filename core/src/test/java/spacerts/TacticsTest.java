package spacerts;

import org.junit.jupiter.api.Test;
import spacerts.net.Command;
import spacerts.net.CommandExecutor;
import spacerts.net.Lockstep;
import spacerts.systems.TacticsSystem;
import spacerts.systems.TacticsSystem.Formation;
import spacerts.systems.TacticsSystem.Member;
import spacerts.systems.TacticsSystem.Role;
import spacerts.units.Ability;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Tactic;
import spacerts.units.TacticUpgrade;
import spacerts.units.Targeting;
import spacerts.units.Unit;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Command Center, the four battle tactics, their upgrades and targeting modes. */
class TacticsTest {

    private static World make() {
        World w = new World(GameSetup.skirmish(91, 1, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        return w;
    }

    private static void run(World w, float seconds) {
        int n = (int) Math.ceil(seconds / Lockstep.STEP);
        for (int i = 0; i < n; i++) w.update(Lockstep.STEP);
    }

    private static Unit spawn(World w, ShipClass c, Faction f, float x, float y) {
        Unit u = w.spawn(c, f, x, y);
        w.flushPending();
        return u;
    }

    private static Faction rival(World w) {
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) return f;
        throw new IllegalStateException();
    }

    /** Player fleet: 2 Bulwarks, 6 Lancers, a Hive and 2 Longbows near the middle of the map; the rest stripped. */
    private static List<Unit> fleet(World w) {
        Faction p = w.player;
        for (Unit u : w.units) // a clean field: only the test's own ships and enemies
            if ((u.owner == p && u != p.mothership) || u.owner.kind == Faction.Kind.NATIVES || u.owner.kind == Faction.Kind.PIRATES) u.kill(true);
        run(w, 0.1f);
        float c = Galaxy.CLASSIC_SIZE / 2;
        List<Unit> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) out.add(spawn(w, ShipClass.BULWARK, p, c + i * 60, c));
        for (int i = 0; i < 6; i++) out.add(spawn(w, ShipClass.LANCER, p, c - 100 + i * 30, c + 120));
        out.add(spawn(w, ShipClass.HIVE, p, c, c - 150));
        for (int i = 0; i < 2; i++) out.add(spawn(w, ShipClass.LONGBOW, p, c + i * 50, c - 260));
        for (Unit u : out) u.retreatOff = true;
        return out;
    }

    /** A practically unkillable, unarmed enemy just ahead of the formation; runs until the formation engages it. */
    private static Unit engage(World w, Formation fm, Faction enemy) {
        Unit e = spawn(w, ShipClass.COLONY_SHIP, enemy, fm.ax + fm.hx * 450, fm.ay + fm.hy * 450);
        e.hp = 1e9f;
        for (int i = 0; i < 600 && fm.state != TacticsSystem.State.ENGAGED; i++) w.update(Lockstep.STEP);
        assertEquals(TacticsSystem.State.ENGAGED, fm.state, "engaged the enemy ahead");
        return e;
    }

    private static List<Member> role(Formation fm, Role r) {
        List<Member> out = new ArrayList<>();
        for (Member m : fm.members) if (m.role == r) out.add(m);
        return out;
    }

    private static float avg(List<Member> ms, boolean x) {
        float s = 0;
        for (Member m : ms) s += x ? m.sx : m.sy;
        return s / Math.max(1, ms.size());
    }

    // ------------------------------------------------------------------ Command Center

    @Test
    void commandCenterUnlocksAndSelectsTactics() {
        World w = make();
        Faction p = w.player;
        assertTrue(ShipClass.COMMAND.isStation());
        boolean listed = false;
        for (ShipClass c : ShipClass.HARVESTER.buildList()) if (c == ShipClass.COMMAND) listed = true;
        assertTrue(listed, "built by Harvesters");
        Unit cc = spawn(w, ShipClass.COMMAND, p, p.mothership.x + 400, p.mothership.y);
        assertEquals("Only one Command Center", w.construction.baseBlocker(p, ShipClass.COMMAND));

        p.energy = p.metal = 10000;
        assertTrue(w.production.blocker(cc, ShipClass.CC_MARTYR).contains("Flank"), "upgrades need their tactic");
        assertNull(w.production.enqueue(cc, ShipClass.CC_BULLDOZER));
        run(w, ShipClass.CC_BULLDOZER.s.buildTime + 1);
        assertTrue(p.tactics.contains(Tactic.BULLDOZER));
        assertEquals(Tactic.BULLDOZER, p.activeTactic, "the first tactic becomes active");
        assertEquals("Already unlocked", w.production.blocker(cc, ShipClass.CC_BULLDOZER));

        CommandExecutor.apply(w, new Command(Command.Type.SET_TACTIC, p.id).arg(Tactic.PINCER.ordinal()));
        assertEquals(Tactic.BULLDOZER, p.activeTactic, "locked tactics can't be picked");
        p.tactics.add(Tactic.PINCER);
        CommandExecutor.apply(w, new Command(Command.Type.SET_TACTIC, p.id).arg(Tactic.PINCER.ordinal()));
        assertEquals(Tactic.PINCER, p.activeTactic);
    }

    @Test
    void tacticAttackFormsUpThenMarches() {
        World w = make();
        Faction p = w.player;
        List<Unit> ships = fleet(w);
        spawn(w, ShipClass.COMMAND, p, p.mothership.x + 400, p.mothership.y);
        p.tactics.add(Tactic.BULLDOZER);
        p.activeTactic = Tactic.BULLDOZER;
        float c = Galaxy.CLASSIC_SIZE / 2;
        CommandExecutor.apply(w, new Command(Command.Type.TACTIC_ATTACK, p.id).units(ships).at(c, c + 3000));
        Formation fm = w.tactics.formationOf(ships.get(0));
        assertNotNull(fm);
        assertEquals(ships.size(), fm.members.size(), "every ship has a Bulldozer role");

        // layout: snipers behind support behind the tanks; infantry ring around the tanks
        assertTrue(avg(role(fm, Role.SNIPER), true) < avg(role(fm, Role.SUPPORT), true));
        assertTrue(avg(role(fm, Role.SUPPORT), true) < avg(role(fm, Role.TANK), true));
        float tx = avg(role(fm, Role.TANK), true);
        for (Member m : role(fm, Role.INFANTRY)) assertTrue(Math.hypot(m.sx - tx, m.sy) > 70, "ring outside the tank block");

        run(w, 12);
        assertNotEquals(TacticsSystem.State.FORMING, fm.state, "formed up");
        float y0 = fm.ay;
        run(w, 10);
        assertTrue(fm.ay > y0 + 200, "marching toward the target: state " + fm.state + " ay " + fm.ay + " y0 " + y0 + " members " + fm.members.size() + " formations " + w.tactics.formations.size());
        for (Member m : fm.members) assertTrue(m.u.dist(fm.slotX(m), fm.slotY(m)) < 140, m.u.s.name + " keeps its slot while marching");

        // a direct order takes a ship out of the formation
        Unit l = role(fm, Role.INFANTRY).get(0).u;
        CommandExecutor.apply(w, new Command(Command.Type.ORDER, p.id).unit(l).at(l.x + 300, l.y));
        assertEquals(0, l.formation);
    }

    @Test
    void shipsTheTacticHasNoPlaceForJustAttackMove() {
        World w = make();
        Faction p = w.player;
        List<Unit> ships = fleet(w);
        spawn(w, ShipClass.COMMAND, p, p.mothership.x + 400, p.mothership.y);
        p.tactics.add(Tactic.FOCUS);
        p.activeTactic = Tactic.FOCUS;
        float c = Galaxy.CLASSIC_SIZE / 2;
        CommandExecutor.apply(w, new Command(Command.Type.TACTIC_ATTACK, p.id).units(ships).at(c, c + 3000));
        Unit hive = null;
        for (Unit u : ships) if (u.cls == ShipClass.HIVE) hive = u;
        assertEquals(0, hive.formation, "Focus has no support line");
        assertEquals(Order.ATTACK_MOVE, hive.order);
        Formation fm = w.tactics.formationOf(ships.get(0));
        assertTrue(avg(role(fm, Role.INFANTRY), true) > avg(role(fm, Role.TANK), true), "infantry in front of the tanks");
        assertTrue(avg(role(fm, Role.TANK), true) > avg(role(fm, Role.SNIPER), true), "snipers at the back");
    }

    @Test
    void withoutACommandCenterEveryoneAttackMoves() {
        World w = make();
        Faction p = w.player;
        List<Unit> ships = fleet(w);
        p.tactics.add(Tactic.BULLDOZER);
        p.activeTactic = Tactic.BULLDOZER;
        CommandExecutor.apply(w, new Command(Command.Type.TACTIC_ATTACK, p.id).units(ships).at(1000, 1000));
        for (Unit u : ships) {
            assertEquals(0, u.formation);
            assertEquals(Order.ATTACK_MOVE, u.order);
        }
    }

    // ------------------------------------------------------------------ layouts

    @Test
    void pincerAndFlankLayouts() {
        World w = make();
        Faction p = w.player;
        List<Unit> ships = fleet(w);
        Formation pin = w.tactics.start(p, Tactic.PINCER, ships, Galaxy.CLASSIC_SIZE / 2, 9000);
        List<Member> inf = role(pin, Role.INFANTRY);
        int left = 0, right = 0;
        for (Member m : inf) {
            if (m.sy > 100) left++;
            if (m.sy < -100) right++;
            assertTrue(m.sx < 0, "wings a little back");
        }
        assertEquals(3, left, "left wing");
        assertEquals(3, right, "right wing");

        Formation fl = w.tactics.start(p, Tactic.FLANK, ships, Galaxy.CLASSIC_SIZE / 2, 9000);
        float tankX = avg(role(fl, Role.TANK), true);
        int rear = 0;
        for (Member m : fl.members)
            if (m.group != 0) {
                rear++;
                assertTrue(m.sx < tankX - 300, "rear group behind the gap");
            }
        assertEquals(3 + 1, rear, "other half of the infantry and of the snipers");
    }

    // ------------------------------------------------------------------ tactic behaviour

    @Test
    void sniperTargetPicks() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        Unit sniper = null;
        for (Unit u : ships) if (u.cls == ShipClass.LONGBOW) sniper = u;
        // two unarmed enemies in range: one near, one far
        Unit near = spawn(w, ShipClass.COLONY_SHIP, r, sniper.x + 250, sniper.y);
        Unit far = spawn(w, ShipClass.COLONY_SHIP, r, sniper.x + 450, sniper.y);
        run(w, 0.3f);
        sniper.shotCount = 0;
        Unit pk = w.tactics.pincerPick(sniper);
        assertEquals(near, pk, "Pincer: first 3 shots at the nearest, got " + (pk == null ? null : pk.cls + " of " + pk.owner.name + " at " + pk.dist(sniper)) + " near at " + near.dist(sniper) + " visible " + w.canSee(p, near));
        sniper.shotCount = 3;
        assertEquals(far, w.tactics.pincerPick(sniper), "then 3 at the farthest");
        sniper.shotCount = 6;
        assertEquals(near, w.tactics.pincerPick(sniper));

        // Focus: the most hull, but hive drones first
        Unit big = spawn(w, ShipClass.BULWARK, r, sniper.x + 600, sniper.y + 100);
        run(w, 0.3f);
        assertEquals(big, w.tactics.focusTarget(sniper), "most hull");
        Unit hive = spawn(w, ShipClass.HIVE, r, sniper.x + 700, sniper.y - 100);
        run(w, 5);
        Unit t = w.tactics.focusTarget(sniper);
        assertNotNull(t);
        assertEquals(ShipClass.DRONE, t.cls, "hivelings before the Hive");
        assertFalse(hive.dead);
    }

    @Test
    void focusSnipersHitTanksHarder() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        Unit sniper = null;
        for (Unit u : ships) if (u.cls == ShipClass.LONGBOW) sniper = u;
        Unit a = spawn(w, ShipClass.BULWARK, r, 1000, 1000), b = spawn(w, ShipClass.BULWARK, r, 1500, 1000);
        a.shield = b.shield = 0;
        float ha = a.hp;
        w.combat.hit(sniper, a, 1f);
        float plain = ha - a.hp;
        Formation fm = w.tactics.start(p, Tactic.FOCUS, ships, 6000, 9000);
        fm.state = TacticsSystem.State.ENGAGED;
        float hb = b.hp;
        w.combat.hit(sniper, b, 1f);
        assertEquals(plain * TacticsSystem.FOCUS_BONUS, hb - b.hp, 1f, "+50% on tanks");
    }

    @Test
    void flankTanksTauntWhenHurtAndAgainOnRecharge() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        Formation fm = w.tactics.start(p, Tactic.FLANK, ships, Galaxy.CLASSIC_SIZE / 2, Galaxy.CLASSIC_SIZE / 2 + 3000);
        Unit tank = role(fm, Role.TANK).get(0).u;
        spawn(w, ShipClass.COLONY_SHIP, r, tank.x, tank.y + 900); // something to fight
        fm.state = TacticsSystem.State.ENGAGED;
        run(w, 0.2f);
        assertEquals(0, tank.abilityCds[Ability.TAUNT.ordinal()], 1e-6, "not hurt yet");
        tank.lastHit = w.time;
        run(w, 0.2f);
        assertTrue(tank.abilityCds[Ability.TAUNT.ordinal()] > 0, "taunts once hurt");
        tank.abilityCds[Ability.TAUNT.ordinal()] = 0;
        run(w, 0.2f);
        assertTrue(tank.abilityCds[Ability.TAUNT.ordinal()] > 0, "and again on recharge");
    }

    @Test
    void flankRearGroupSwingsAroundBothSides() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        float c = Galaxy.CLASSIC_SIZE / 2;
        Formation fm = w.tactics.start(p, Tactic.FLANK, ships, c, c + 3000);
        run(w, 12); // form up
        engage(w, fm, r);
        run(w, 0.1f);
        float hx = fm.hx, hy = fm.hy;
        run(w, 45);
        float[] tc = {0, 0};
        int tn = 0;
        for (Member m : role(fm, Role.TANK)) { tc[0] += m.u.x; tc[1] += m.u.y; tn++; }
        tc[0] /= tn;
        tc[1] /= tn;
        boolean leftDone = false, rightDone = false;
        for (Member m : fm.members) {
            if (m.group == 0) continue;
            assertEquals(TacticsSystem.FLANK_WAYPOINTS, m.waypoint, m.u.s.name + " finished the arc");
            if (m.group == 1) leftDone = true;
            else rightDone = true;
        }
        StringBuilder dbg = new StringBuilder();
        for (Member m : fm.members) dbg.append(m.u.cls).append(" g").append(m.group).append(" wp").append(m.waypoint).append(" ");
        assertTrue(leftDone && rightDone, "both halves swung around: " + dbg + " state " + fm.state + " formations " + w.tactics.formations.size());
        assertTrue(hx * hx + hy * hy > 0.9f);
    }

    @Test
    void pincerWingsCloseInOnceTanksAreHurt() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        float c = Galaxy.CLASSIC_SIZE / 2;
        Formation fm = w.tactics.start(p, Tactic.PINCER, ships, c, c + 3000);
        run(w, 12);
        engage(w, fm, r);
        run(w, 1);
        for (Member m : role(fm, Role.INFANTRY)) assertEquals(Order.MOVE, m.u.order, "wings hold their sides");
        Unit tank = role(fm, Role.TANK).get(0).u;
        tank.hp = tank.maxHp() * 0.5f;
        run(w, 0.5f);
        boolean closing = false;
        for (Member m : role(fm, Role.INFANTRY)) // ordered toward the enemy's flank, far from the held slot
            if (m.waypoint > 0 || Math.hypot(m.u.tx - fm.slotX(m), m.u.ty - fm.slotY(m)) > 200) closing = true;
        StringBuilder dbg = new StringBuilder();
        for (Member m : role(fm, Role.INFANTRY)) dbg.append(m.u.order).append(" wp").append(m.waypoint).append(" d").append((int) m.u.dist(fm.slotX(m), fm.slotY(m))).append(" g").append(m.group).append("; ");
        assertTrue(closing || role(fm, Role.INFANTRY).isEmpty(), "wings move out to surround: tank " + tank.hp / tank.maxHp() + " dead " + tank.dead + " tank in fm " + (tank.formation) + " " + dbg + " state " + fm.state + " formations " + w.tactics.formations.size());
    }

    // ------------------------------------------------------------------ upgrades

    @Test
    void martyrChargeStunsTheSniperAndItsNeighbours() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        p.tacticUps.add(TacticUpgrade.MARTYR);
        Formation fm = w.tactics.start(p, Tactic.FLANK, ships, 6000, 9000);
        Unit l = role(fm, Role.INFANTRY).get(0).u;
        Unit sniper = spawn(w, ShipClass.LONGBOW, r, l.x + 200, l.y);
        Unit close = spawn(w, ShipClass.COLONY_SHIP, r, sniper.x + 40, sniper.y);
        Unit away = spawn(w, ShipClass.COLONY_SHIP, r, sniper.x + 120, sniper.y);
        run(w, 0.3f); // vision
        // the Lancer has been moving to its slot: put the enemies back next to it
        sniper.x = l.x + 200;
        sniper.y = l.y;
        close.x = sniper.x + 40;
        close.y = sniper.y;
        close.anchorX = close.x;
        close.anchorY = close.y;
        away.x = sniper.x + 120;
        away.y = sniper.y;
        away.anchorX = away.x;
        away.anchorY = away.y;
        sniper.anchorX = sniper.x;
        sniper.anchorY = sniper.y;
        w.update(Lockstep.STEP); // rebuild the spatial hash
        w.combat.applyDamage(l, 1e9f, true, null);
        assertFalse(l.dead, "the killing blow becomes a charge: tactic " + w.tactics.tacticOf(l) + " formation " + l.formation + " sees " + w.canSee(p, sniper) + " dist " + l.dist(sniper) + " range " + l.range());
        assertEquals(sniper, l.martyrTarget);
        boolean stunned = false;
        for (int i = 0; i < 120 && !l.dead; i++) { // the bystander stays right next to the (backpedalling) sniper
            close.x = sniper.x + 40;
            close.y = sniper.y;
            close.anchorX = close.x;
            close.anchorY = close.y;
            w.update(Lockstep.STEP);
        }
        assertTrue(l.dead, "it dies in the blast");
        stunned = sniper.stunTimer > 0;
        assertTrue(stunned, "the sniper is stunned");
        assertTrue(close.stunTimer > 0, "so is an enemy within 50: close at " + close.dist(sniper) + " dead " + close.dead + " sniper stun " + sniper.stunTimer + " away stun " + away.stunTimer);
        assertEquals(0, away.stunTimer, 1e-6, "but not one further away");
    }

    @Test
    void cripplingLineRammingAndShieldWall() {
        World w = make();
        Faction p = w.player, r = rival(w);
        List<Unit> ships = fleet(w);
        p.tacticUps.add(TacticUpgrade.CRIPPLE);
        Formation pin = w.tactics.start(p, Tactic.PINCER, ships, 6000, 9000);
        pin.state = TacticsSystem.State.ENGAGED;
        Unit tank = role(pin, Role.TANK).get(0).u;
        Unit runner = spawn(w, ShipClass.LANCER, r, tank.x + 200, tank.y);
        runner.vx = 100; // fleeing away from the tank
        w.combat.hit(tank, runner, 1f);
        assertEquals(TacticsSystem.CRIPPLE_MULT, runner.slowMult, 1e-6, "slowed by 5%");

        // Shield Wall: less sniper damage, a longer Taunt
        World w2 = make();
        Faction p2 = w2.player, r2 = rival(w2);
        List<Unit> s2 = fleet(w2);
        Formation bull = w2.tactics.start(p2, Tactic.BULLDOZER, s2, 6000, 9000);
        Unit t2 = role(bull, Role.TANK).get(0).u;
        Unit enemySniper = spawn(w2, ShipClass.LONGBOW, r2, t2.x + 700, t2.y);
        t2.shield = 0;
        float h0 = t2.hp;
        w2.combat.hit(enemySniper, t2, 1f);
        float plain = h0 - t2.hp;
        p2.tacticUps.add(TacticUpgrade.SHIELD_WALL);
        float h1 = t2.hp;
        w2.combat.hit(enemySniper, t2, 1f);
        assertEquals(plain * TacticsSystem.SHIELD_WALL_TAKEN, h1 - t2.hp, 1f, "40% less sniper damage");
        Unit faraway = spawn(w2, ShipClass.LANCER, r2, t2.x + 850, t2.y + 100);
        run(w2, 0.3f);
        assertNull(w2.behavior.useAbility(t2, Ability.TAUNT));
        assertEquals(t2, faraway.forcedTarget, "Taunt reaches 1000");

        // Ramming Plates: bumping an enemy hurts it badly
        p2.tacticUps.add(TacticUpgrade.RAM);
        bull.state = TacticsSystem.State.ENGAGED;
        Unit victim = spawn(w2, ShipClass.BULWARK, r2, t2.x + t2.radius + 10, t2.y);
        victim.shield = 0;
        float hv = victim.hp;
        run(w2, 0.2f);
        assertTrue(victim.dead || victim.hp < hv - 1000, "rammed: " + (hv - victim.hp));
    }

    @Test
    void replenishRequeuesLostShipsAndSendsThemToTheFight() {
        World w = make();
        Faction p = w.player;
        List<Unit> ships = fleet(w);
        p.tacticUps.add(TacticUpgrade.REPLENISH);
        p.energy = p.metal = 20000;
        Unit station = spawn(w, ShipClass.FACTORY, p, p.mothership.x + 500, p.mothership.y);
        Formation fm = w.tactics.start(p, Tactic.BULLDOZER, ships, 6000, 9000);
        p.hasTacticTarget = true;
        p.tacticX = 6000;
        p.tacticY = 9000;
        List<Member> inf = role(fm, Role.INFANTRY);
        for (int i = 0; i < 3; i++) inf.get(i).u.kill(false); // a whole Lancer squad's worth
        run(w, 0.1f);
        assertEquals(1, station.queue.items.size(), "one Lancer squad re-queued");
        assertEquals(ShipClass.LANCER, station.queue.items.get(0));
        run(w, ShipClass.LANCER.s.buildTime + 1);
        int heading = 0;
        for (Unit u : w.units)
            if (!u.dead && u.owner == p && u.cls == ShipClass.LANCER && u.formation == 0 && u.order == Order.ATTACK_MOVE
                    && Math.hypot(u.tx - 6000, u.ty - 9000) < 100) heading++;
        assertEquals(3, heading, "the replacements head to the attack destination");
    }

    // ------------------------------------------------------------------ targeting

    @Test
    void targetingModes() {
        World w = make();
        Faction p = w.player, r = rival(w);
        Unit l = spawn(w, ShipClass.LANCER, p, 3000, 3000);
        Unit near = spawn(w, ShipClass.WISP, r, 3150, 3000); // weak, close
        Unit far = spawn(w, ShipClass.BULWARK, r, 3400, 3000); // tough, further
        run(w, 0.3f);
        l.targeting = Targeting.NEAREST;
        assertEquals(near, w.behavior.acquire(l, 600, null, 0));
        l.targeting = Targeting.FARTHEST;
        assertEquals(far, w.behavior.acquire(l, 600, null, 0));
        l.targeting = Targeting.MOST_HEALTH;
        assertEquals(far, w.behavior.acquire(l, 600, null, 0));
        l.targeting = Targeting.LEAST_HEALTH;
        assertEquals(near, w.behavior.acquire(l, 600, null, 0));
        l.targeting = Targeting.RANDOM;
        assertNotNull(w.behavior.acquire(l, 600, null, 0));
        CommandExecutor.apply(w, new Command(Command.Type.SET_TARGETING, p.id).unit(l).arg(Targeting.FARTHEST.ordinal()));
        assertEquals(Targeting.FARTHEST, l.targeting);
    }

    @Test
    void tacticalAttacksAreDeterministic() {
        World a = make(), b = make();
        for (World w : new World[]{a, b}) {
            List<Unit> ships = fleet(w);
            spawn(w, ShipClass.COMMAND, w.player, w.player.mothership.x + 400, w.player.mothership.y);
            w.player.tactics.add(Tactic.FLANK);
            w.player.activeTactic = Tactic.FLANK;
            CommandExecutor.apply(w, new Command(Command.Type.TACTIC_ATTACK, w.player.id).units(ships).at(2000, 2000));
        }
        for (int i = 0; i < 1500; i++) {
            a.update(Lockstep.STEP);
            b.update(Lockstep.STEP);
        }
        assertEquals(Lockstep.checksum(a), Lockstep.checksum(b));
    }

    // ------------------------------------------------------------------ bots

    /** A rich rival bot with a Research Hub, at a game time where it may start on tactics. */
    private static World botWorld(Difficulty d) {
        World w = new World(GameSetup.skirmish(91, 1, d));
        GalaxyGenerator.generate(w);
        w.flushPending();
        Faction r = rival(w);
        spawn(w, ShipClass.HUB, r, r.mothership.x - 500, r.mothership.y);
        r.energy = 30000;
        r.metal = 30000;
        w.time = 800;
        return w;
    }

    @Test
    void hardBotsBuildACommandCenterAndUnlockBulldozer() {
        World w = botWorld(Difficulty.HARD);
        Faction r = rival(w);
        assertTrue(r.ai.usesTactics());
        run(w, 240);
        assertEquals(1, w.count(r, ShipClass.COMMAND), "built one Command Center");
        assertTrue(r.tactics.contains(Tactic.BULLDOZER), "unlocked Bulldozer");
        assertEquals(Tactic.BULLDOZER, r.activeTactic);
        assertTrue(r.has(TacticUpgrade.SHIELD_WALL), "rich enough for the Shield Wall upgrade");
    }

    @Test
    void normalBotsLeaveTacticsAlone() {
        World w = botWorld(Difficulty.NORMAL);
        Faction r = rival(w);
        assertFalse(r.ai.usesTactics());
        run(w, 240);
        assertEquals(0, w.count(r, ShipClass.COMMAND));
        assertTrue(r.tactics.isEmpty());
    }

    @Test
    void hardBotAssaultsMarchAsABulldozer() {
        World w = botWorld(Difficulty.HARD);
        Faction r = rival(w);
        Unit ms = r.mothership;
        spawn(w, ShipClass.COMMAND, r, ms.x + 500, ms.y);
        r.tactics.add(Tactic.BULLDOZER);
        r.activeTactic = Tactic.BULLDOZER;
        for (int i = 0; i < 4; i++) spawn(w, ShipClass.BULWARK, r, ms.x + 300 + i * 60, ms.y + 300);
        for (int i = 0; i < 12; i++) spawn(w, ShipClass.LANCER, r, ms.x + 200 + i * 30, ms.y + 400);
        for (int i = 0; i < 4; i++) spawn(w, ShipClass.LONGBOW, r, ms.x + 300 + i * 50, ms.y + 480);
        Formation fm = null;
        for (int i = 0; i < (int) (120 / Lockstep.STEP) && fm == null; i++) {
            w.update(Lockstep.STEP);
            for (Formation f : w.tactics.formations) if (f.f == r) fm = f;
        }
        assertNotNull(fm, "the assault formed up");
        assertEquals(Tactic.BULLDOZER, fm.tactic);
        assertTrue(fm.members.size() >= 6, "the grouped core joined, had " + fm.members.size());
        // the bot leaves its formation alone while it marches
        int size = fm.members.size();
        run(w, 3);
        assertTrue(w.tactics.formations.contains(fm), "still marching");
        assertEquals(size, fm.members.size(), "no ship was pulled out by the bot");
        for (Member m : fm.members) assertEquals(fm.id, m.u.formation);
    }
}

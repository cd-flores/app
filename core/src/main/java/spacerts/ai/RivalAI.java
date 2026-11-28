package spacerts.ai;

import spacerts.net.CommandExecutor;
import spacerts.research.Tech;
import spacerts.systems.ConstructionSystem;
import spacerts.systems.TacticsSystem;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Tactic;
import spacerts.units.TacticUpgrade;
import spacerts.units.Unit;
import spacerts.world.Body;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rival empire brain: economy, expansion, research, army build-up, assaults and defence. */
public class RivalAI {
    public enum Mode { BUILDUP, ASSAULT, DEFEND }

    private final World w;
    private final Faction f;
    public Mode mode = Mode.BUILDUP;
    private float think;
    private float assaultX, assaultY, assaultStartPower;
    private Body assaultPlanet;
    private Unit assaultUnit;
    /** Game time when a (warned) assault actually moves out. */
    private float launchAt;

    private static final Tech[] RESEARCH_ORDER = {
            Tech.HARVEST_1, Tech.CARGO, Tech.WEAPONS_1, Tech.ARMOR_1, Tech.LAST_STAND, Tech.HUB_TIER_2, Tech.CARRIER_TECH,
            Tech.HOUSING, Tech.SHIELDS, Tech.ENGINES, Tech.DEEP_MINING, Tech.SURVEY_DRILLS, Tech.AEGIS_TECH,
            Tech.PHANTOM_TECH, Tech.WEAPONS_2, Tech.ARMOR_2, Tech.HARVEST_2, Tech.RAPID_ASSEMBLY, Tech.PLANET_TURRETS,
            Tech.MS_ARMOR, Tech.MS_REPAIR, Tech.HUB_TIER_3, Tech.LEVIATHAN_TECH, Tech.NOVA_CANNON, Tech.SENSORS,
            Tech.FLEET_COMMAND, Tech.SNIPER_DEFLECTORS, Tech.EMERGENCY_THRUSTERS, Tech.RAPID_REFIT};

    // lists rebuilt every think
    private final List<Unit> harvesters = new ArrayList<>(), army = new ArrayList<>(), colonyShips = new ArrayList<>(),
            surveyors = new ArrayList<>(), colonies = new ArrayList<>(), outposts = new ArrayList<>(), producers = new ArrayList<>();

    public RivalAI(World w, Faction f) {
        this.w = w;
        this.f = f;
        this.think = 1f + f.id * 0.37f;
    }

    /** Bots sit completely still for the first eight minutes of a game. */
    public static final float START_DELAY = 480f;

    /** Colonies a bot may hold: COLONY_START at first, COLONY_STEP more every COLONY_PERIOD seconds, at most COLONY_MAX. */
    public static final int COLONY_START = 3, COLONY_STEP = 2, COLONY_MAX = 14;
    public static final float COLONY_PERIOD = 15 * 60f;

    public int colonyLimit() {
        return Math.min(COLONY_MAX, COLONY_START + COLONY_STEP * (int) (w.time / COLONY_PERIOD));
    }

    public void update(float dt) {
        if (w.time < START_DELAY) return;
        think -= dt;
        if (think > 0) return;
        think = f.difficulty.thinkInterval(); // easier bots make decisions less often
        Unit ms = f.mothership;
        if (ms == null || ms.dead) return;
        scan();
        economy(ms);
        expansion(ms);
        research();
        commandCenter();
        military(ms);
        command(ms);
    }

    private void scan() {
        harvesters.clear(); army.clear(); colonyShips.clear(); surveyors.clear(); colonies.clear(); outposts.clear(); producers.clear();
        for (Unit u : w.units) {
            if (u.dead || u.owner != f) continue;
            if (u.s.harvester) harvesters.add(u);
            else if (u.s.colonizer) colonyShips.add(u);
            else if (u.s.surveyor) surveyors.add(u);
            else if (u.cls == ShipClass.COLONY) colonies.add(u);
            else if (u.cls == ShipClass.OUTPOST) outposts.add(u);
            else if (u.cls.isWarship() && !u.retreating) army.add(u); // retreating ships repair before rejoining
            if (u.cls == ShipClass.FACTORY || u.cls == ShipClass.SHIPYARD) producers.add(u);
        }
    }

    private int pending(ShipClass c) {
        return w.queued(f, c);
    }

    private Unit leastBusy() {
        Unit best = null;
        for (Unit p : producers) if (best == null || p.queue.items.size() < best.queue.items.size()) best = p;
        return best;
    }

    private void economy(Unit ms) {
        int target = Math.min(22, 6 + colonies.size() * 2 + (int) (w.time / 200f));
        if (harvesters.size() + pending(f.race.worker) < target && ms.queue.items.size() < 3)
            w.production.enqueue(ms, f.race.worker);
        // a couple of Patrol Ships once the Mothership is tier II
        if (ms.tier >= 2 && w.count(f, ShipClass.PATROL) + pending(ShipClass.PATROL) < 2 && ms.queue.items.size() < 3)
            w.production.enqueue(ms, ShipClass.PATROL);
        // Mining Barges once a factory is up: 1 + 1 per 3 colonies, at most 4
        int barges = 0;
        for (Unit h : harvesters) if (h.cls == ShipClass.BARGE) barges++;
        Unit fac = leastBusy();
        if (fac != null && fac.queue.items.size() < 2 && barges + pending(ShipClass.BARGE) < Math.min(4, 1 + colonies.size() / 3))
            w.production.enqueue(fac, ShipClass.BARGE);
        for (Unit h : harvesters) if (h.order == Order.IDLE) w.economy.autoAssign(h);
    }

    private void expansion(Unit ms) {
        // colony ships
        Set<Body> claimed = new HashSet<>();
        for (Unit c : colonyShips) if (c.body != null) claimed.add(c.body);
        boolean housingTight = f.popCap - f.popUsed < 15;
        if ((housingTight || w.time > 120) && colonyShips.size() + pending(ShipClass.COLONY_SHIP) == 0 && colonies.size() < colonyLimit()) {
            Unit fac = leastBusy();
            if (fac != null && nearestColonizable(ms, claimed) != null) w.production.enqueue(fac, ShipClass.COLONY_SHIP);
        }
        for (Unit c : colonyShips) {
            if (c.order != Order.COLONIZE) {
                Body b = nearestColonizable(c, claimed);
                if (b != null) {
                    c.command(Order.COLONIZE, b.x, b.y);
                    c.body = b;
                    claimed.add(b);
                }
            }
        }
        construction(ms);
        // surveyors
        if (f.hub != null && !f.hub.dead) {
            Set<Body> claimedS = new HashSet<>();
            for (Unit s : surveyors) if (s.body != null) claimedS.add(s.body);
            int want = 2 + f.hubTier() * 2;
            if (outposts.size() + surveyors.size() + pending(ShipClass.SURVEYOR) < want && f.hub.queue.items.isEmpty()
                    && nearestSurveyable(f.hub, claimedS) != null)
                w.production.enqueue(f.hub, ShipClass.SURVEYOR);
            for (Unit s : surveyors) {
                if (s.order != Order.SURVEY) {
                    Body b = nearestSurveyable(s, claimedS);
                    if (b != null) {
                        s.command(Order.SURVEY, b.x, b.y);
                        s.body = b;
                        claimedS.add(b);
                    }
                }
            }
        }
    }

    /** Harvester construction: factories, the research hub, planet defenses and menders. */
    private void construction(Unit ms) {
        int factories = 0;
        for (Unit p : producers) if (p.cls == ShipClass.FACTORY) factories++;
        factories += w.construction.building(f, ShipClass.FACTORY, null);
        // first factory right away, more as the empire grows
        int wantFactories = Math.min(ConstructionSystem.maxStations(f), 1 + colonies.size() / 3 + (w.time > 900 ? 1 : 0));
        if (factories == 0 || (factories < wantFactories && w.time > 420 && f.canAfford(600, 600, NO_RARE))) {
            Unit anchor = factories == 0 || colonies.isEmpty() ? ms : colonies.get(w.rng.nextInt(colonies.size()));
            build(ShipClass.FACTORY, anchor, null);
            return;
        }
        if (w.time > 200 && (f.hub == null || f.hub.dead) && w.construction.building(f, ShipClass.HUB, null) == 0) {
            build(ShipClass.HUB, ms, null);
            return;
        }
        // a second lab once the Mothership reaches tier II
        if (ms.tier >= 2 && f.hubsAlive() == 1 && f.canAfford(1000, 1000, NO_RARE)
                && w.construction.baseBlocker(f, ShipClass.HUB) == null) {
            build(ShipClass.HUB, ms, null);
            return;
        }
        // Hard and Insane bots add a Command Center once the Hub stands and the economy can spare it
        if (usesTactics() && w.time > tacticsFrom() && f.hub != null && !f.hub.dead && f.canAfford(900, 900, NO_RARE)
                && w.construction.baseBlocker(f, ShipClass.COMMAND) == null) {
            build(ShipClass.COMMAND, ms, null);
            return;
        }
        if (w.time > 600 && f.canAfford(500, 700, NO_RARE)
                && w.construction.building(f, ShipClass.DEF_TURRET, null) + w.construction.building(f, ShipClass.DEF_SHIELD, null) == 0) {
            Unit weakest = null;
            for (Unit c : colonies)
                if (c.home != null && c.home.defensePoints() < spacerts.world.Body.MAX_DEFENSE
                        && (weakest == null || c.home.defensePoints() < weakest.home.defensePoints())) weakest = c;
            if (weakest != null) {
                // mix: turrets first, then shield, aiming for 3 turret / 2 shield points
                Body b = weakest.home;
                ShipClass kind = b.turretPoints <= b.shieldPoints || b.shieldPoints >= 2 ? ShipClass.DEF_TURRET : ShipClass.DEF_SHIELD;
                if (b.turretPoints >= 3) kind = ShipClass.DEF_SHIELD;
                build(kind, weakest, b);
                return;
            }
        }
        // resource structures: solar collectors at stars and miners at asteroid fields near home
        if (w.time > 150 && !harvesters.isEmpty() && f.canAfford(450, 450, NO_RARE)
                && w.construction.building(f, ShipClass.SOLAR, null) + w.construction.building(f, ShipClass.MINER, null) == 0) {
            int solars = 0, miners = 0;
            for (Unit u : w.units) {
                if (u.dead || u.owner != f) continue;
                if (u.cls == ShipClass.SOLAR) solars++;
                if (u.cls == ShipClass.MINER) miners++;
            }
            int want = Math.min(5, 1 + colonies.size() / 2 + (int) (w.time / 900f));
            ShipClass kind = solars <= miners ? ShipClass.SOLAR : ShipClass.MINER;
            if ((kind == ShipClass.SOLAR ? solars : miners) < want) {
                Body site = extractorSite(ms, kind);
                if (site != null) {
                    build(kind, ms, site);
                    return;
                }
            }
        }
        if (w.time > 300 && f.canAfford(400, 400, NO_RARE) && w.construction.building(f, ShipClass.MENDER, null) == 0) {
            int menders = 0;
            menders = w.count(f, ShipClass.MENDER);
            if (menders < army.size() / 10) build(ShipClass.MENDER, ms, null);
        }
    }

    private static final int[] NO_RARE = new int[spacerts.research.RareItem.COUNT];

    /** Closest star (solar) or asteroid / metal planet (miner) to home that a collector or miner can still use. */
    private Body extractorSite(Unit ms, ShipClass kind) {
        Unit h = harvesters.get(0);
        Body best = null;
        float bd = 4500;
        for (Body b : w.bodies) {
            if (b.dead) continue;
            if (kind == ShipClass.SOLAR ? b.kind != Body.Kind.STAR : !(b.kind == Body.Kind.ASTEROID || (b.isPlanet() && b.type.minable))) continue;
            float d = ms.dist(b.x, b.y);
            for (Unit c : colonies) d = Math.min(d, c.dist(b.x, b.y));
            if (d >= bd) continue;
            if (w.construction.blocker(h, kind, b, b.x, b.y) != null) continue;
            bd = d;
            best = b;
        }
        return best;
    }

    /** Sends the nearest free harvester to build c near the anchor unit. */
    private void build(ShipClass c, Unit anchor, Body body) {
        Unit best = null;
        float bd = Float.MAX_VALUE;
        for (Unit h : harvesters) {
            if (h.buildCls != null) continue;
            float d = h.dist(anchor);
            if (d < bd) { bd = d; best = h; }
        }
        if (best == null) return;
        float x = anchor.x, y = anchor.y;
        if (c.needsSite()) {
            boolean found = false;
            for (int i = 0; i < 24 && !found; i++) {
                float a = w.rng.nextFloat() * 6.283f, d = anchor.radius + 160 + w.rng.nextFloat() * 260;
                x = anchor.x + (float) Math.cos(a) * d;
                y = anchor.y + (float) Math.sin(a) * d;
                found = w.construction.siteBlocker(f, x, y, c.s.radius) == null;
            }
            if (!found) return;
        }
        w.construction.order(best, c, body, x, y);
    }

    private Body nearestColonizable(Unit from, Set<Body> claimed) {
        Body best = null;
        float bd = 7000;
        for (Body b : w.bodies) {
            if (!b.colonizable() || claimed.contains(b)) continue;
            float d = from.dist(b.x, b.y);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private Body nearestSurveyable(Unit from, Set<Body> claimed) {
        Body best = null;
        float bd = 6000;
        for (Body b : w.bodies) {
            if (!b.canSurvey() || claimed.contains(b)) continue;
            float d = from.dist(b.x, b.y);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private void research() {
        if (spacerts.systems.ProductionSystem.freeLab(f) == 0) return; // both labs busy (or none standing)
        for (Tech t : RESEARCH_ORDER) {
            if (w.production.researchBlocker(f, t) != null) continue;
            if (f.canAfford(t.energy + 150, t.metal + 100, t.rare)) {
                w.production.startResearch(f, t);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ Command Center and tactics

    /** Hard and Insane bots build a Command Center and fight their assaults as a Bulldozer. */
    public boolean usesTactics() {
        return f.difficulty.ordinal() >= Difficulty.HARD.ordinal();
    }

    /** Game time from which the bot starts on its Command Center. */
    private float tacticsFrom() {
        return f.difficulty == Difficulty.INSANE ? 540f : 720f;
    }

    /** Bulldozer formations need a long enough march to form up, at least a tank and this many ships. */
    static final float TACTIC_MIN_MARCH = 1500f;
    static final int TACTIC_MIN_SHIPS = 6;

    /** Unlocks Bulldozer at the Command Center, keeps it active, and adds its upgrades once rich. */
    private void commandCenter() {
        if (!usesTactics()) return;
        Unit cc = null;
        for (Unit u : w.units)
            if (!u.dead && u.owner == f && u.cls == ShipClass.COMMAND) { cc = u; break; }
        if (cc == null || !cc.queue.items.isEmpty()) return;
        if (!f.tactics.contains(Tactic.BULLDOZER)) {
            if (f.canAfford(ShipClass.CC_BULLDOZER.s.energy + 150, ShipClass.CC_BULLDOZER.s.metal + 100, NO_RARE))
                w.production.enqueue(cc, ShipClass.CC_BULLDOZER);
            return;
        }
        f.activeTactic = Tactic.BULLDOZER;
        if (f.energy > 2500 && f.metal > 2500) {
            ShipClass up = !f.has(TacticUpgrade.SHIELD_WALL) ? ShipClass.CC_SHIELD_WALL : !f.has(TacticUpgrade.RAM) ? ShipClass.CC_RAM : null;
            if (up != null) w.production.enqueue(cc, up);
        }
    }

    /**
     * Starts a Bulldozer toward the assault target with the grouped ships around the leader, when the bot can:
     * a Command Center, Bulldozer active, no formation already out, and a target far enough away to form up.
     */
    private void tacticalLaunch(Unit lead) {
        if (!usesTactics() || f.activeTactic != Tactic.BULLDOZER || !CommandExecutor.hasCommandCenter(w, f)) return;
        if (lead.dist(assaultX, assaultY) < TACTIC_MIN_MARCH) return;
        for (Unit u : army) if (u.formation != 0) return;
        List<Unit> members = new ArrayList<>();
        int tanks = 0;
        for (Unit u : army) {
            if (u.target != null || u.cls == ShipClass.DEFENDER || u.cls == ShipClass.DRONE || u.s.stationary) continue;
            TacticsSystem.Role r = TacticsSystem.role(u.cls);
            if (!TacticsSystem.uses(Tactic.BULLDOZER, r) || u.dist(lead) > 1200) continue;
            members.add(u);
            if (r == TacticsSystem.Role.TANK) tanks++;
        }
        if (tanks == 0 || members.size() < TACTIC_MIN_SHIPS) return;
        f.hasTacticTarget = true;
        f.tacticX = assaultX;
        f.tacticY = assaultY;
        w.tactics.start(f, Tactic.BULLDOZER, members, assaultX, assaultY);
    }

    /** A direct order from the bot; it takes the ship out of any tactic formation first. */
    private static void order(Unit u, Order o, float x, float y) {
        u.formation = 0;
        u.command(o, x, y);
    }

    private float armyPower() {
        float p = 0;
        for (Unit u : army) p += u.power();
        return p;
    }

    /** Warships alive (retreating ones too) plus warships waiting in production queues, counted per ship. */
    public int warships() {
        int n = 0;
        for (Unit u : w.units) {
            if (u.dead || u.owner != f) continue;
            if (u.cls.isWarship()) n++;
            if (u.queue != null)
                for (ShipClass c : u.queue.items) if (c.isWarship()) n += c.s.squad;
        }
        return n;
    }

    /** True when the difficulty's ship cap stops this bot from building more warships. */
    public boolean atShipCap() {
        int cap = f.difficulty.shipCap;
        return cap >= 0 && warships() >= cap;
    }

    private void military(Unit ms) {
        if (f.energy < 250 && f.metal < 250) return;
        if (atShipCap()) return;
        // keep housing free for a colony ship while there are worlds left to settle, or expansion stalls
        if (f.popCap - f.popUsed <= 4 && colonyShips.isEmpty() && colonies.size() < colonyLimit()
                && nearestColonizable(ms, new HashSet<>()) != null) return;
        // Hard and Insane bots guard a tier II Mothership with up to 2 Defenders once they are rich
        if (f.difficulty.ordinal() >= spacerts.world.Difficulty.HARD.ordinal() && ms.tier >= 2 && f.energy > 4000 && f.metal > 4500
                && w.count(f, ShipClass.DEFENDER) + pending(ShipClass.DEFENDER) < 2 && ms.queue.items.size() < 3
                && w.production.enqueue(ms, ShipClass.DEFENDER) == null) return;
        Unit p = leastBusy();
        if (p == null || p.queue.items.size() >= 3) return;
        // capital ship first when available
        if (f.race.botCapital != null && f.has(Tech.LEVIATHAN_TECH) && w.production.enqueue(p, f.race.botCapital) == null) return;
        int wisps = 0;
        for (Unit u : army) if (u.cls == f.race.scout) wisps++;
        if (wisps < 2) { w.production.enqueue(p, f.race.scout); return; }
        // upgrade a factory once the economy can carry it
        if (w.time > 600 && f.energy > 1800 && f.metal > 1800) {
            ShipClass up = p.tier == 1 ? ShipClass.FACTORY_TIER_2 : p.tier == 2 ? ShipClass.FACTORY_TIER_3 : null;
            if (up != null && w.production.enqueue(p, up) == null) return;
        }
        if (p.tier >= 3 && f.race.botBattleship != null && w.rng.nextBoolean() && w.production.enqueue(p, f.race.botBattleship) == null) return;
        ShipClass[] pool = f.race.botArmy;
        if (pool.length == 0) return;
        for (int tries = 0; tries < 4; tries++) {
            ShipClass c = pool[w.rng.nextInt(pool.length)];
            if (w.production.enqueue(p, c) == null) return;
        }
    }

    private void command(Unit ms) {
        float power = armyPower();
        float threshold = (20 + w.time / 60f * 1.6f) * f.difficulty.assaultThreshold;
        spacerts.world.StarSystem home = w.systemAt(ms.x, ms.y);
        float rallyA = (float) Math.atan2(home.cy - ms.y, home.cx - ms.x);
        float rx = ms.x + (float) Math.cos(rallyA) * 350, ry = ms.y + (float) Math.sin(rallyA) * 350;

        // defence takes priority when something of ours near home is hit
        boolean homeThreat = w.time - f.lastAttackedTime < 6 && Math.hypot(f.lastAttackX - ms.x, f.lastAttackY - ms.y) < 3500;
        if (homeThreat && mode != Mode.DEFEND && (mode != Mode.ASSAULT || power < 10)) {
            mode = Mode.DEFEND;
            for (Unit u : army) order(u, Order.ATTACK_MOVE, f.lastAttackX, f.lastAttackY);
            return;
        }
        switch (mode) {
            case DEFEND:
                if (w.time - f.lastAttackedTime > 15) {
                    mode = Mode.BUILDUP;
                    for (Unit u : army) order(u, Order.MOVE, rx, ry);
                } else {
                    for (Unit u : army) if (u.order == Order.IDLE) u.command(Order.ATTACK_MOVE, f.lastAttackX, f.lastAttackY);
                }
                break;
            case BUILDUP:
                for (Unit u : army)
                    if (u.order == Order.IDLE && u.target == null && u.dist(rx, ry) > 600) u.command(Order.MOVE, rx + w.rng.nextFloat() * 200 - 100, ry + w.rng.nextFloat() * 200 - 100);
                if (power >= threshold && army.size() >= 6) {
                    if (pickAssault(ms)) {
                        mode = Mode.ASSAULT;
                        assaultStartPower = power;
                        launchAt = w.time + warn(army.size());
                        if (launchAt <= w.time) attackMove();
                    }
                }
                break;
            case ASSAULT:
                if (power < assaultStartPower * 0.35f) {
                    mode = Mode.BUILDUP;
                    for (Unit u : army) order(u, Order.MOVE, rx, ry);
                    break;
                }
                if (w.time < launchAt) { // warned the player; gathering at the rally point before moving out
                    for (Unit u : army)
                        if (u.order == Order.IDLE && u.target == null && u.dist(rx, ry) > 500) u.command(Order.MOVE, rx, ry);
                    break;
                }
                boolean done = assaultPlanet != null ? !assaultPlanet.guarded() : (assaultUnit == null || assaultUnit.dead);
                if (done) {
                    if (!pickAssault(ms)) {
                        mode = Mode.BUILDUP;
                        for (Unit u : army) order(u, Order.MOVE, rx, ry);
                        break;
                    }
                    float delay = warn(army.size());
                    if (delay > 0) {
                        launchAt = w.time + delay;
                        break;
                    }
                }
                attackMove();
                break;
        }
    }

    /**
     * Sends the army at the assault target as one group: stragglers far from the main body regroup on it first
     * instead of trickling into the enemy one by one.
     */
    private void attackMove() {
        if (army.isEmpty()) return;
        float cx = 0, cy = 0;
        for (Unit u : army) { cx += u.x; cy += u.y; }
        cx /= army.size();
        cy /= army.size();
        // the leader is the ship closest to the army's centre
        Unit lead = army.get(0);
        for (Unit u : army) if (u.dist(cx, cy) < lead.dist(cx, cy)) lead = u;
        int together = 0;
        for (Unit u : army) if (u.dist(lead) < 900) together++;
        boolean grouped = together >= army.size() * 0.7f || lead.dist(assaultX, assaultY) < 1800;
        if (grouped) tacticalLaunch(lead); // Hard / Insane: the grouped core marches in as a Bulldozer
        for (Unit u : army) {
            if (u.target != null || u.formation != 0) continue; // already fighting, or the formation drives it
            if (grouped) {
                boolean onTarget = u.order == Order.ATTACK_MOVE && Math.hypot(u.tx - assaultX, u.ty - assaultY) < 400;
                if (!onTarget) u.command(Order.ATTACK_MOVE, assaultX + w.rng.nextFloat() * 160 - 80, assaultY + w.rng.nextFloat() * 160 - 80);
            } else if (u.dist(lead) > 600) {
                if (u.order != Order.MOVE || Math.hypot(u.tx - lead.x, u.ty - lead.y) > 400) u.command(Order.MOVE, lead.x, lead.y);
            } else if (u.order == Order.ATTACK_MOVE) {
                u.idle(); // hold with the leader until the stragglers catch up
            }
        }
    }

    /** Warns the player when the chosen target is theirs; returns how long to wait before moving out. */
    private float warn(int ships) {
        if (assaultUnit == null || !assaultUnit.owner.isPlayer()) return 0;
        Unit t = assaultUnit;
        String where = t.home != null ? t.home.name : t.s.name;
        w.notify(t.owner, f.name + " fleet (" + ships + " ships) is preparing to attack your " + where + "!", World.ALERT);
        if (t.owner == w.player) w.alert(t.x, t.y);
        return f.difficulty.warningDelay;
    }

    /** Picks the next assault target: a guarded planet nearby, or the closest enemy structure. */
    private boolean pickAssault(Unit ms) {
        assaultPlanet = null;
        assaultUnit = null;
        Body bestPlanet = null;
        float bp = 5500;
        for (Body b : w.bodies) {
            if (b.dead || !b.guarded()) continue;
            if (b.type == spacerts.world.PlanetType.RUINS && w.time < 900) continue;
            float d = ms.dist(b.x, b.y);
            if (d < bp) { bp = d; bestPlanet = b; }
        }
        // nearest structure of the player and of other rivals, then pick one by the difficulty's player share
        Unit nearPlayer = null, nearRival = null;
        float dp = Float.MAX_VALUE, dr = Float.MAX_VALUE;
        for (Unit u : w.units) {
            if (u.dead || !f.hostileTo(u.owner) || !u.owner.isMajor()) continue;
            if (!(u.cls.isBuilding() || u.cls.isFlagship())) continue;
            float d = ms.dist(u);
            if (u.owner.isPlayer()) {
                // Easy: hit the player's colonies and outposts first; their Mothership only when nothing else is left
                if (u.cls.isFlagship() && f.difficulty == spacerts.world.Difficulty.EASY) d += 100000;
                if (d < dp) { dp = d; nearPlayer = u; }
            } else if (d < dr) { dr = d; nearRival = u; }
        }
        Unit bestEnemy;
        if (nearPlayer != null && nearRival != null)
            bestEnemy = w.rng.nextFloat() < f.difficulty.playerShare ? nearPlayer : nearRival;
        else bestEnemy = nearPlayer != null ? nearPlayer : nearRival;
        // early game: only fight natives for planets; empires are fair game after the difficulty's first strike time
        float first = f.difficulty.firstStrike;
        if (w.time < first) bestEnemy = null;
        boolean preferPlanet = bestPlanet != null && (bestEnemy == null || w.time < first + 600 || w.rng.nextFloat() < 0.4f);
        if (preferPlanet) {
            assaultPlanet = bestPlanet;
            assaultX = bestPlanet.x;
            assaultY = bestPlanet.y;
            return true;
        }
        if (bestEnemy != null) {
            assaultUnit = bestEnemy;
            assaultX = bestEnemy.x;
            assaultY = bestEnemy.y;
            return true;
        }
        return false;
    }
}
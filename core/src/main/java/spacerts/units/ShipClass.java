package spacerts.units;

import spacerts.research.Tech;

import static spacerts.units.ArmorType.*;

/**
 * Every unit and structure type. Counter triangle:
 * Lancer (infantry) beats Longbow (sniper), Longbow beats Bulwark (tank), Bulwark beats Lancer.
 */
public enum ShipClass {
    MOTHERSHIP(new Spec("Mothership", "Your flagship. Builds Harvesters. If it dies, you lose.")
            .hp(6000).shield(1500, 15).armor(0.35f).speed(28).weapon(450, 28, 0.5f)
            .vision(950).radius(60).type(HEAVY).producer()),
    HARVESTER(new Spec("Harvester", "Collects energy and metal. Also builds Ship Factories, the Research Hub, planet defenses and Menders.")
            .cost(50, 0, 1).hp(120).speed(115).vision(350).radius(10).build(6).harvester()),
    COLONY_SHIP(new Spec("Colony Ship", "Settles a habitable planet for housing. Defended worlds must be cleared first.")
            .cost(200, 150, 2).hp(260).armor(0.1f).speed(75).vision(400).radius(15).build(16).colonizer()),
    SURVEYOR(new Spec("Surveyor", "Lands on an uninhabitable planet and builds a Research Outpost that extracts rare items.")
            .cost(120, 80, 1).hp(110).speed(95).vision(400).radius(10).build(12).surveyor()),

    WISP(new Spec("Wisp", "SCOUT. Fast, huge vision, detects cloaked ships. Q: Sensor Ping reveals a wide area.")
            .cost(30, 20, 1).hp(60).speed(230).weapon(130, 4, 0.5f).vision(1150).radius(8).build(5)
            .ability(Ability.PING).detector()),
    LANCER(new Spec("Lancer", "INFANTRY squad of 3. Cheap, fast swarm DPS. Bonus damage vs snipers.")
            .cost(120, 120, 1).squad(3).hp(110).armor(0.1f).speed(165).weapon(170, 7, 0.25f)
            .bonus(ARTILLERY, 2.5f).vision(520).radius(9).build(7)),
    BULWARK(new Spec("Bulwark", "TANK. Huge HP and shields, flak splash vs infantry. Q: Taunt pulls enemy fire.")
            .cost(120, 200, 4).hp(900).shield(350, 12).armor(0.4f).speed(95).weapon(210, 10, 0.6f)
            .splash(70).bonus(LIGHT, 2f).vision(460).radius(20).build(14).type(HEAVY).ability(Ability.TAUNT)),
    LONGBOW(new Spec("Longbow", "SNIPER. Very long range armor-piercing shots, must stop to fire. Fragile; blind up close.")
            .cost(150, 120, 3).hp(160).armor(0.05f).speed(80).weapon(900, 130, 5.5f).minRange(220)
            .piercing().mustStop().bonus(HEAVY, 1.8f).vision(450).radius(14).build(14).type(ARTILLERY)),
    MENDER(new Spec("Mender", "MOBILE REPAIR STATION. Crawls along. Repairs every allied ship and structure in a wide area. Unarmed. Built by Harvesters.")
            .cost(150, 150, 6).hp(600).shield(200, 8).armor(0.2f).speed(12).heal(400, 15)
            .vision(550).radius(18).build(18)),
    HIVE(new Spec("Hive", "CARRIER. Launches and replaces up to 6 attack drones.")
            .cost(300, 350, 6).hp(1400).shield(400, 10).armor(0.3f).speed(62).drones(6)
            .vision(650).radius(28).build(26).type(HEAVY).tech(Tech.CARRIER_TECH)),
    DRONE(new Spec("Drone", "Hive attack drone.")
            .hp(55).speed(195).weapon(140, 4.5f, 0.3f).bonus(ARTILLERY, 2f).vision(350).radius(6).internal()),
    PHANTOM(new Spec("Phantom", "RARE SNIPER. Cloaked until it fires. Long range, armor-piercing.")
            .cost(250, 200, 4).rare(0, 0, 2, 1, 0).hp(200).armor(0.1f).speed(95).weapon(1000, 170, 6f).minRange(220)
            .piercing().mustStop().bonus(HEAVY, 1.8f).cloak().vision(520).radius(14).build(18)
            .type(ARTILLERY).tech(Tech.PHANTOM_TECH)),
    AEGIS(new Spec("Aegis", "RARE TANK. Projects a shield bubble: allies nearby take 30% less damage. Q: Taunt.")
            .cost(250, 350, 6).rare(0, 3, 0, 0, 0).hp(1400).shield(900, 25).armor(0.4f).speed(66)
            .weapon(230, 14, 0.6f).splash(80).bonus(LIGHT, 2f).aura(320).vision(480).radius(24).build(22)
            .type(HEAVY).ability(Ability.TAUNT).tech(Tech.AEGIS_TECH)),
    LEVIATHAN(new Spec("Leviathan", "MASSIVE CAPITAL SHIP. Fires at 4 targets at once. Q: Nova Cannon (needs research).")
            .cost(2000, 2500, 20).rare(3, 0, 0, 5, 3).hp(12000).shield(4000, 40).armor(0.5f).speed(36)
            .weapon(700, 45, 0.35f).multi(4).vision(950).radius(70).build(90).type(HEAVY)
            .ability(Ability.NOVA).detector().cap(1).tech(Tech.LEVIATHAN_TECH)),

    TURRET(new Spec("Defense Turret", "Planetary defense battery.")
            .hp(700).shield(200, 8).armor(0.3f).weapon(520, 24, 0.9f).vision(600).radius(16)
            .type(STRUCTURE).stationary().internal()),
    GUARD(new Spec("Native Guard", "Native defense ship. Respawns while the planet's turrets stand.")
            .hp(220).armor(0.15f).speed(125).weapon(230, 9, 0.5f).vision(500).radius(11).internal()),
    RAIDER(new Spec("Pirate Raider", "Fast pirate attack craft.")
            .hp(150).armor(0.1f).speed(175).weapon(200, 8, 0.4f).vision(550).radius(10).internal()),
    GUNSHIP(new Spec("Pirate Gunship", "Heavy pirate warship with splash cannons.")
            .hp(650).shield(100, 4).armor(0.3f).speed(92).weapon(320, 18, 0.7f).splash(50)
            .vision(550).radius(20).type(HEAVY).internal()),
    DREADNOUGHT(new Spec("Pirate Dreadnought", "Pirate capital ship.")
            .hp(5000).shield(1200, 15).armor(0.4f).speed(45).weapon(550, 36, 0.35f).multi(3)
            .vision(750).radius(48).type(HEAVY).internal()),

    HUB(new Spec("Research Hub", "Researches technology and builds Surveyors. Built by Harvesters. Only one allowed.")
            .cost(400, 400, 0).hp(2500).shield(600, 10).armor(0.3f).vision(700).radius(34).build(40)
            .speed(28).type(STRUCTURE).producer()), // mobile, as slow as the Mothership
    SHIPYARD(new Spec("Orbital Shipyard", "Second production site in orbit of a colony.")
            .cost(300, 300, 0).hp(1600).shield(300, 8).armor(0.3f).vision(550).radius(22).build(30)
            .type(STRUCTURE).stationary().producer()),
    FACTORY(new Spec("Ship Factory", "Builds every ship except Harvesters. Built by Harvesters; build as many as you like.")
            .cost(300, 300, 0).hp(1800).shield(400, 10).armor(0.3f).vision(600).radius(30).build(30)
            .speed(28).type(STRUCTURE).producer()), // mobile, as slow as the Mothership
    SOLAR(new Spec("Solar Collector", "Built beside a star. Produces energy on its own - no hauling. Up to 2 per star.")
            .cost(250, 200, 0).hp(900).shield(200, 6).armor(0.2f).vision(450).radius(18).build(25)
            .type(STRUCTURE).stationary()),
    MINER(new Spec("Asteroid Miner", "Built beside an asteroid or metal-rich planet. Mines metal on its own, moving on to nearby asteroids as they run out.")
            .cost(200, 250, 0).hp(1000).shield(150, 5).armor(0.25f).vision(450).radius(18).build(25)
            .type(STRUCTURE).stationary()),
    DEF_TURRET(new Spec("Turret Upgrade", "Planet upgrade (1 of 5 points): +2 defense turrets on one of your colonies.")
            .cost(150, 250, 0).build(20).radius(16).type(STRUCTURE).stationary().internal()),
    DEF_SHIELD(new Spec("Shield Upgrade", "Planet upgrade (1 of 5 points): +400 planetary shield and faster shield recharge on one of your colonies.")
            .cost(250, 150, 0).build(20).radius(16).type(STRUCTURE).stationary().internal()),
    OUTPOST(new Spec("Research Outpost", "Extracts rare items from its planet over time. Fragile.")
            .hp(350).armor(0.1f).vision(450).radius(12).type(STRUCTURE).stationary().internal()),
    COLONY(new Spec("Colony", "Settled world. Provides housing, slowly produces energy and metal, and is a resource drop-off point. Harvesters can build defenses here.")
            .hp(1500).shield(300, 6).armor(0.3f).vision(750).radius(30).type(STRUCTURE).stationary()
            .internal());

    public final Spec s;

    ShipClass(Spec s) {
        this.s = s;
    }

    private static final ShipClass[] MS_BUILD = {HARVESTER};
    private static final ShipClass[] FACTORY_BUILD = {COLONY_SHIP, WISP, LANCER, BULWARK, LONGBOW, HIVE, PHANTOM, AEGIS, LEVIATHAN};
    /** Legacy: shipyards from older saves keep working. */
    private static final ShipClass[] YARD_BUILD = {COLONY_SHIP, WISP, LANCER, BULWARK, LONGBOW, HIVE, PHANTOM, AEGIS};
    private static final ShipClass[] HUB_BUILD = {SURVEYOR};
    private static final ShipClass[] HARVESTER_BUILD = {FACTORY, HUB, DEF_TURRET, DEF_SHIELD, MENDER, SOLAR, MINER};

    /** Planet defense upgrades: each spends one of a colony's 5 upgrade points. */
    public boolean isPlanetUpgrade() {
        return this == DEF_TURRET || this == DEF_SHIELD;
    }

    /** Resource structures placed next to a star (solar) or an asteroid / metal planet (miner). */
    public boolean isExtractor() {
        return this == SOLAR || this == MINER;
    }

    /** Built by clicking a body (a colony, star or asteroid) rather than open space. */
    public boolean targetsBody() {
        return isPlanetUpgrade() || isExtractor();
    }
    private static final ShipClass[] NONE = {};

    public ShipClass[] buildList() {
        switch (this) {
            case MOTHERSHIP: return MS_BUILD;
            case FACTORY: return FACTORY_BUILD;
            case SHIPYARD: return YARD_BUILD;
            case HUB: return HUB_BUILD;
            case HARVESTER: return HARVESTER_BUILD;
            default: return NONE;
        }
    }

    /** Structures a harvester must fly to and place (the rest are built where it stands or on a colony). */
    public boolean needsSite() {
        return this == HUB || this == FACTORY;
    }

    public boolean isWarship() {
        return !s.internal && !s.stationary && !s.producer && !s.harvester && !s.colonizer && !s.surveyor;
    }

    /** Structures, including the Ship Factory and Research Hub, which can move slowly. */
    public boolean isBuilding() {
        return s.stationary || this == FACTORY || this == HUB;
    }
}

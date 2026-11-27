package spacerts.units;

import java.util.EnumMap;

/**
 * A playable race: its flagship, its builder, what each of its producers can make, and how its bots build an army.
 * Every empire has one (Faction.race). Ships and structures themselves stay in ShipClass; a race just picks from them.
 *
 * Adding a race:
 * 1. add its new ships to ShipClass (Spec lines), a role in TacticsSystem.role() and a shape in WorldRenderer;
 * 2. add a constant here and fill its lists in the static block below (flagship needs Spec.flagship(), builder Spec.builder());
 * 3. append it at the END of this enum (its ordinal goes over the network).
 */
public enum Race {
    COALITION("Coalition", "The original roster: Mothership, Harvesters, Lancers, Bulwarks, Longbows and Hives.",
            ShipClass.MOTHERSHIP, ShipClass.HARVESTER, ShipClass.WISP);

    public final String title, desc;
    /** Losing it loses the game; it gets the Mothership tiers. */
    public final ShipClass flagship;
    /** Gathers resources and builds structures; colonies hand out one each. */
    public final ShipClass worker;
    /** The scout bots keep two of. */
    public final ShipClass scout;

    private final EnumMap<ShipClass, ShipClass[]> builds = new EnumMap<>(ShipClass.class);
    /** Bot army mix: picked at random (repeats weight a ship). */
    public ShipClass[] botArmy = {};
    /** Bot capital ship (built first once researched) and tier III battleship; null = none. */
    public ShipClass botCapital, botBattleship;

    Race(String title, String desc, ShipClass flagship, ShipClass worker, ShipClass scout) {
        this.title = title;
        this.desc = desc;
        this.flagship = flagship;
        this.worker = worker;
        this.scout = scout;
    }

    private static final ShipClass[] NONE = {};

    /** What a producer of class c makes for this race (empty if c builds nothing). */
    public ShipClass[] buildList(ShipClass c) {
        ShipClass[] l = builds.get(c);
        return l != null ? l : NONE;
    }

    public boolean canBuild(ShipClass producer, ShipClass c) {
        for (ShipClass b : buildList(producer)) if (b == c) return true;
        return false;
    }

    public static Race of(int ordinal) {
        Race[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : COALITION;
    }

    public static Race named(String name) {
        if (name != null) for (Race r : values()) if (r.name().equals(name)) return r;
        return COALITION;
    }

    static {
        Race r = COALITION;
        r.builds.put(ShipClass.MOTHERSHIP, new ShipClass[]{ShipClass.HARVESTER, ShipClass.PATROL, ShipClass.DEFENDER, ShipClass.MS_TIER_2,
                ShipClass.MS_TIER_3, ShipClass.STELLAR_REFINEMENT});
        r.builds.put(ShipClass.FACTORY, new ShipClass[]{ShipClass.COLONY_SHIP, ShipClass.WISP, ShipClass.LANCER, ShipClass.BULWARK,
                ShipClass.LONGBOW, ShipClass.HIVE, ShipClass.BARGE, ShipClass.CORSAIR, ShipClass.TEMPEST, ShipClass.PYRE, ShipClass.PHANTOM,
                ShipClass.AEGIS, ShipClass.SOVEREIGN, ShipClass.LEVIATHAN, ShipClass.FACTORY_TIER_2, ShipClass.FACTORY_TIER_3});
        // legacy: shipyards from older saves keep working
        r.builds.put(ShipClass.SHIPYARD, new ShipClass[]{ShipClass.COLONY_SHIP, ShipClass.WISP, ShipClass.LANCER, ShipClass.BULWARK,
                ShipClass.LONGBOW, ShipClass.HIVE, ShipClass.PHANTOM, ShipClass.AEGIS, ShipClass.BARGE});
        r.builds.put(ShipClass.HUB, new ShipClass[]{ShipClass.SURVEYOR});
        r.builds.put(ShipClass.COMMAND, new ShipClass[]{ShipClass.CC_BULLDOZER, ShipClass.CC_PINCER, ShipClass.CC_FLANK, ShipClass.CC_FOCUS,
                ShipClass.CC_MARTYR, ShipClass.CC_CRIPPLE, ShipClass.CC_RAM, ShipClass.CC_SHIELD_WALL, ShipClass.CC_REPLENISH});
        // planet upgrades (DEF_TURRET / DEF_SHIELD) are ordered from the colony itself, not from this list
        r.builds.put(ShipClass.HARVESTER, new ShipClass[]{ShipClass.FACTORY, ShipClass.HUB, ShipClass.MENDER, ShipClass.SOLAR,
                ShipClass.MINER, ShipClass.COMMAND, ShipClass.BEACON, ShipClass.PLATFORM, ShipClass.REFINERY, ShipClass.SENSOR,
                ShipClass.ION_CANNON, ShipClass.ASCENSION});
        r.botArmy = new ShipClass[]{ShipClass.LANCER, ShipClass.LANCER, ShipClass.LANCER, ShipClass.BULWARK, ShipClass.BULWARK,
                ShipClass.LONGBOW, ShipClass.LONGBOW, ShipClass.HIVE, ShipClass.AEGIS, ShipClass.PHANTOM, ShipClass.CORSAIR,
                ShipClass.TEMPEST, ShipClass.PYRE};
        r.botCapital = ShipClass.LEVIATHAN;
        r.botBattleship = ShipClass.SOVEREIGN;
    }
}

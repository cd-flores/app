package spacerts.world;

/**
 * One empire's running totals for the end-of-match table. Only the UI reads them: they never steer the simulation,
 * but every machine counts them the same way, so they agree in LAN games without being sent.
 */
public class MatchStats {
    /** Enemy ships and enemy structures this empire destroyed. */
    public int kills, structuresDestroyed;
    public int shipsBuilt, structuresBuilt, shipsLost, structuresLost;
    /** Hull plus shield damage this empire's units dealt to others. */
    public double damageDealt;
    public double energyGathered, metalGathered, energySpent, metalSpent;
    /** Research finished; worlds settled. */
    public int techs, colonies;
    /** Cost (energy + metal) of the living warships, sampled once per game second, and its highest value. */
    public double armyNow, peakArmy;
    /** Game time the empire was knocked out, or -1 while it's in the game. */
    public float eliminatedAt = -1;

    public double gathered() { return energyGathered + metalGathered; }

    public double spent() { return energySpent + metalSpent; }

    public void sampleArmy(double value) {
        armyNow = value;
        if (value > peakArmy) peakArmy = value;
    }
}

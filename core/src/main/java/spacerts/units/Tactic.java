package spacerts.units;

/** Battle formations unlocked at the Command Center; one is active at a time. */
public enum Tactic {
    BULLDOZER("Bulldozer", "Infantry ring the tanks, support behind, snipers at the back. The blob pushes forward; snipers shoot whatever is closest to the tanks."),
    PINCER("Pincer", "Tanks in front, infantry wings to the sides, support behind the tanks, snipers at the back. Once the tanks take damage the wings close in around the enemy. Snipers switch between the nearest and farthest target every 3 shots."),
    FLANK("Flank", "Tanks, half the infantry and half the snipers in front; the other half waits behind. Damaged tanks taunt (and again on every recharge) while the rear half swings around both sides to strike 900 past the tanks."),
    FOCUS("Focus", "Infantry in front, tanks behind them, snipers at the back. Snipers kill hive drones first, then the target with the most hull, with +50% damage on tanks and Hives.");

    public final String title, desc;

    Tactic(String title, String desc) {
        this.title = title;
        this.desc = desc;
    }
}

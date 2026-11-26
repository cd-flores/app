package spacerts.units;

/** Command Center upgrades that strengthen one tactic (Replenish works for all). */
public enum TacticUpgrade {
    MARTYR("Martyr Charge", Tactic.FLANK, "Flank infantry about to die charge a nearby enemy sniper and explode, stunning it and everything within 50 for 0.5 s."),
    CRIPPLE("Crippling Line", Tactic.PINCER, "Pincer tanks slow enemies that turn and run by 5%."),
    RAM("Ramming Plates", Tactic.BULLDOZER, "Bulldozer tanks creep forward and deal massive damage to enemy ships they bump into."),
    SHIELD_WALL("Shield Wall", Tactic.BULLDOZER, "Bulldozer tanks take 40% less damage from snipers and their Taunt reaches 1000."),
    REPLENISH("Replenish", null, "Ships lost from a formation are re-queued at your Ship Stations (normal price) and sent to the attack destination.");

    public final String title;
    /** The tactic it needs (null: any tactic). */
    public final Tactic needs;
    public final String desc;

    TacticUpgrade(String title, Tactic needs, String desc) {
        this.title = title;
        this.needs = needs;
        this.desc = desc;
    }
}

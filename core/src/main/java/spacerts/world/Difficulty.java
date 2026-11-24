package spacerts.world;

/** Tuning knobs for rival empires and pirates. Chosen in the main menu. */
public enum Difficulty {
    EASY("Easy", 0.7f, 1.6f, 30 * 60, 0.25f, 0.65f, true, 20f),
    NORMAL("Normal", 1.0f, 1.15f, 17 * 60, 0.5f, 0.9f, false, 20f),
    HARD("Hard", 1.25f, 0.85f, 12 * 60, 0.7f, 1.2f, false, 0f);

    public final String title;
    /** Multiplies rival harvest and colony income. */
    public final float rivalIncome;
    /** Multiplies the army power a rival needs before it launches an assault. */
    public final float assaultThreshold;
    /** Game time (s) before rivals may attack other empires (they still fight natives earlier). */
    public final float firstStrike;
    /** Chance that a rival choosing between the player and another rival goes for the player. */
    public final float playerShare;
    /** Multiplies the pirate wave budget. */
    public final float pirateBudget;
    /** Player starts with extra resources and an extra Lancer squad. */
    public final boolean playerBonus;
    /** Seconds between the "fleet incoming" warning and the rival fleet actually moving out. */
    public final float warningDelay;

    Difficulty(String title, float rivalIncome, float assaultThreshold, float firstStrike, float playerShare,
               float pirateBudget, boolean playerBonus, float warningDelay) {
        this.title = title;
        this.rivalIncome = rivalIncome;
        this.assaultThreshold = assaultThreshold;
        this.firstStrike = firstStrike;
        this.playerShare = playerShare;
        this.pirateBudget = pirateBudget;
        this.playerBonus = playerBonus;
        this.warningDelay = warningDelay;
    }

    public Difficulty easier() { return values()[Math.max(0, ordinal() - 1)]; }
    public Difficulty harder() { return values()[Math.min(values().length - 1, ordinal() + 1)]; }

    public static Difficulty parse(String s) {
        if (s != null) for (Difficulty d : values()) if (d.name().equals(s)) return d;
        return NORMAL;
    }
}

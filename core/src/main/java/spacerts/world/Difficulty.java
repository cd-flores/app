package spacerts.world;

/** Tuning knobs for rival empires (bots, per faction) and pirates (per game). Chosen in the main menu / lobby. */
public enum Difficulty {
    EASY("Easy", 0.7f, 1.6f, 30 * 60, 0.25f, 0.65f, true, 20f, 64),
    NORMAL("Normal", 1.0f, 1.15f, 17 * 60, 0.5f, 0.9f, false, 20f, 120),
    HARD("Hard", 1.25f, 0.85f, 12 * 60, 0.7f, 1.2f, false, 0f, 200),
    INSANE("Insane", 1.5f, 0.7f, 10 * 60, 0.8f, 1.5f, false, 0f, -1);

    public final String title;
    /** Multiplies rival harvest and colony income. */
    public final float rivalIncome;
    /** Multiplies the army power a rival needs before it launches an assault. */
    public final float assaultThreshold;
    /** Game time (s) before rivals may attack other empires (they still fight natives earlier). */
    public final float firstStrike;
    /** Chance that a rival choosing between a human and another rival goes for the human. */
    public final float playerShare;
    /** Multiplies the pirate wave budget. */
    public final float pirateBudget;
    /** Player starts with extra resources and an extra Lancer squad (Campaign only). */
    public final boolean playerBonus;
    /** Seconds between the "fleet incoming" warning and the rival fleet actually moving out. */
    public final float warningDelay;
    /** Most warships a bot keeps regardless of housing; -1 = uncapped. */
    public final int shipCap;

    Difficulty(String title, float rivalIncome, float assaultThreshold, float firstStrike, float playerShare,
               float pirateBudget, boolean playerBonus, float warningDelay, int shipCap) {
        this.title = title;
        this.rivalIncome = rivalIncome;
        this.assaultThreshold = assaultThreshold;
        this.firstStrike = firstStrike;
        this.playerShare = playerShare;
        this.pirateBudget = pirateBudget;
        this.playerBonus = playerBonus;
        this.warningDelay = warningDelay;
        this.shipCap = shipCap;
    }

    /** Seconds between a bot's decisions: slow on Easy, quick on Insane. */
    public float thinkInterval() {
        switch (this) {
            case EASY: return 20f;
            case NORMAL: return 10f;
            case HARD: return 5f;
            default: return 1.5f;
        }
    }

    public Difficulty easier() { return values()[Math.max(0, ordinal() - 1)]; }
    public Difficulty harder() { return values()[Math.min(values().length - 1, ordinal() + 1)]; }
    /** Cycles through all levels, wrapping around. */
    public Difficulty next() { return values()[(ordinal() + 1) % values().length]; }

    public static Difficulty parse(String s) {
        if (s != null) for (Difficulty d : values()) if (d.name().equals(s)) return d;
        return NORMAL;
    }
}

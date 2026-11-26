package spacerts.units;

/** Which enemy a ship picks among those in reach (like tower targeting in Bloons). */
public enum Targeting {
    NEAREST("Nearest"), FARTHEST("Farthest"), MOST_HEALTH("Most health"), LEAST_HEALTH("Least health"), RANDOM("Random");

    public final String title;

    Targeting(String title) {
        this.title = title;
    }

    public Targeting next() {
        return values()[(ordinal() + 1) % values().length];
    }
}

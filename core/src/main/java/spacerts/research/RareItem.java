package spacerts.research;

import com.badlogic.gdx.graphics.Color;

/**
 * Rare items. The first five are extracted by Research Outposts on uninhabitable planets; the last four are
 * scarce finds that only turn up now and then while mining, collecting starlight, salvaging and fighting.
 */
public enum RareItem {
    PYROCRYSTAL("Pyrocrystal", "Pyro", new Color(1f, 0.45f, 0.2f, 1f)),
    CRYO_CORE("Cryo Core", "Cryo", new Color(0.55f, 0.85f, 1f, 1f)),
    PLASMA_ESSENCE("Plasma Essence", "Plasma", new Color(0.95f, 0.5f, 1f, 1f)),
    VOID_SHARD("Void Shard", "Void", new Color(0.62f, 0.62f, 1f, 1f)),
    RELIC_FRAGMENT("Relic Fragment", "Relic", new Color(1f, 0.85f, 0.35f, 1f)),
    // ---- scarce finds
    DARK_MATTER("Dark Matter", "Dark", new Color(0.55f, 0.4f, 0.75f, 1f)),
    STAR_ESSENCE("Star Essence", "Star", new Color(1f, 0.95f, 0.6f, 1f)),
    NEUTRONIUM("Neutronium", "Neut", new Color(0.7f, 0.9f, 0.85f, 1f)),
    EXOTIC_MATTER("Exotic Matter", "Exotic", new Color(0.4f, 1f, 0.7f, 1f));

    public static final int COUNT = 9;
    /** Index of the first scarce find (shown in the top bar only once found). */
    public static final int FIRST_SCARCE = 5;

    public final String title;
    public final String shortName;
    public final Color color;

    RareItem(String title, String shortName, Color color) {
        this.title = title;
        this.shortName = shortName;
        this.color = color;
    }

    public boolean scarce() {
        return ordinal() >= FIRST_SCARCE;
    }

    public static int[] cost(int pyro, int cryo, int plasma, int voidShard, int relic) {
        return cost(pyro, cryo, plasma, voidShard, relic, 0, 0, 0, 0);
    }

    public static int[] cost(int pyro, int cryo, int plasma, int voidShard, int relic, int dark, int star, int neutron, int exotic) {
        return new int[]{pyro, cryo, plasma, voidShard, relic, dark, star, neutron, exotic};
    }

    /** "2 Pyro 1 Relic" style text, empty when no rare cost. */
    public static String describe(int[] rare) {
        StringBuilder sb = new StringBuilder();
        for (RareItem r : values()) {
            int n = rare[r.ordinal()];
            if (n > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(n).append(' ').append(r.shortName);
            }
        }
        return sb.toString();
    }
}

package spacerts.world;

import com.badlogic.gdx.graphics.Color;
import spacerts.research.RareItem;

public enum PlanetType {
    HABITABLE("Habitable World", true, false, null, 10, new Color(0.3f, 0.72f, 0.45f, 1f)),
    DEFENDED("Fortified World", true, false, null, 25, new Color(0.3f, 0.55f, 0.85f, 1f)),
    VOLCANIC("Volcanic Planet", false, true, RareItem.PYROCRYSTAL, 0, new Color(0.72f, 0.28f, 0.14f, 1f)),
    ICE("Ice Planet", false, true, RareItem.CRYO_CORE, 0, new Color(0.75f, 0.88f, 0.95f, 1f)),
    GAS("Gas Giant", false, true, RareItem.PLASMA_ESSENCE, 0, new Color(0.78f, 0.58f, 0.82f, 1f)),
    CRYSTAL("Crystal World", false, true, RareItem.VOID_SHARD, 0, new Color(0.48f, 0.43f, 0.9f, 1f)),
    RUINS("Ancient Ruins", false, false, RareItem.RELIC_FRAGMENT, 0, new Color(0.62f, 0.53f, 0.36f, 1f));

    public final String title;
    public final boolean habitable;
    public final boolean minable;
    public final RareItem rare;
    public final int housing;
    public final Color color;

    PlanetType(String title, boolean habitable, boolean minable, RareItem rare, int housing, Color color) {
        this.title = title;
        this.habitable = habitable;
        this.minable = minable;
        this.rare = rare;
        this.housing = housing;
        this.color = color;
    }
}

package spacerts.units;

import java.util.ArrayList;
import java.util.List;

public class ProductionQueue {
    public static final int MAX = 8;
    public final List<ShipClass> items = new ArrayList<>();
    public float progress;

    public boolean isEmpty() {
        return items.isEmpty();
    }
}

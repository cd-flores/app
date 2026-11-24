package spacerts.units;

public enum Ability {
    NONE("-"),
    PING("Sensor Ping"),
    TAUNT("Taunt"),
    NOVA("Nova Cannon");

    public final String title;

    Ability(String title) {
        this.title = title;
    }
}

package spacerts.net;

import spacerts.research.Tech;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Body;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * One player action, resolved on the issuing machine (what was clicked) and applied to the simulation by
 * {@link CommandExecutor} on every machine at the same tick. Units are referenced by id, bodies by their (fixed)
 * position, classes and techs by name.
 */
public final class Command {
    public enum Type {
        /** Right-click: move / attack-move / attack a unit / harvest, colonize or survey a body; producers get a rally point. */
        ORDER,
        STOP, ENQUEUE, CANCEL_QUEUE, RESEARCH, CANCEL_RESEARCH,
        /** A harvester builds cls at (x, y) or on the body. */
        BUILD,
        /** The nearest free harvester builds a planet upgrade (cls) on the colony body. */
        UPGRADE_PLANET,
        ABILITY, RETREAT, SET_FLAG, CLEAR_FLAG, AUTO_DEFENSE,
        /** Rally Stations on (arg 1) or off (arg 0). */
        RALLY_STATIONS,
        /** Scuttle the units for a 25% refund (Delete); SURRENDER gives up the whole empire. */
        DESTROY, SURRENDER,
        /** Chat: name = text, arg = 1 for team only. */
        CHAT,
        /** A cheat code line (single player only): name = the line. */
        CHEAT,
        /** Tactics: SET_TACTIC arg = Tactic ordinal; TACTIC_ATTACK = units + target point (+ target unit); SET_TARGETING arg = Targeting ordinal. */
        SET_TACTIC, TACTIC_ATTACK, SET_TARGETING,
        /** Game flow (shared in LAN games): arg = 1 pause / 0 resume; SPEED arg = 1..4. */
        PAUSE, SPEED,
        /** The faction's player left the game: a bot takes over. */
        LEAVE
    }

    public Type type;
    public int faction;
    public int[] units = new int[0];
    public float x, y;
    /** A unit id (attack target, producer), or -1. */
    public int target = -1;
    public boolean hasBody;
    public float bodyX, bodyY;
    /** ShipClass or Tech name, or null. */
    public String name;
    /** Small extra value: attack-move flag, queue index, on/off, pause state, speed. */
    public int arg;
    public boolean shift;

    public Command() {}

    public Command(Type type, int faction) {
        this.type = type;
        this.faction = faction;
    }

    public Command units(List<Unit> list) {
        int n = 0;
        for (Unit u : list) if (!u.dead) n++;
        units = new int[n];
        int i = 0;
        for (Unit u : list) if (!u.dead) units[i++] = u.id;
        return this;
    }

    public Command unit(Unit u) {
        units = new int[]{u.id};
        return this;
    }

    public Command at(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public Command body(Body b) {
        if (b != null) {
            hasBody = true;
            bodyX = b.x;
            bodyY = b.y;
        }
        return this;
    }

    public Command cls(ShipClass c) {
        name = c.name();
        return this;
    }

    public Command tech(Tech t) {
        name = t.name();
        return this;
    }

    public Command arg(int a) {
        arg = a;
        return this;
    }

    public Command target(Unit u) {
        target = u != null ? u.id : -1;
        return this;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(type.ordinal());
        out.writeByte(faction);
        out.writeShort(units.length);
        for (int id : units) out.writeInt(id);
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeInt(target);
        out.writeBoolean(hasBody);
        out.writeFloat(bodyX);
        out.writeFloat(bodyY);
        out.writeUTF(name != null ? name : "");
        out.writeInt(arg);
        out.writeBoolean(shift);
    }

    public static Command read(DataInputStream in) throws IOException {
        Command c = new Command();
        c.type = Type.values()[in.readByte()];
        c.faction = in.readByte();
        int n = in.readShort();
        c.units = new int[n];
        for (int i = 0; i < n; i++) c.units[i] = in.readInt();
        c.x = in.readFloat();
        c.y = in.readFloat();
        c.target = in.readInt();
        c.hasBody = in.readBoolean();
        c.bodyX = in.readFloat();
        c.bodyY = in.readFloat();
        String s = in.readUTF();
        c.name = s.isEmpty() ? null : s;
        c.arg = in.readInt();
        c.shift = in.readBoolean();
        return c;
    }
}

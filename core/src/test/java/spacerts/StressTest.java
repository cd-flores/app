package spacerts;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import spacerts.net.Lockstep;
import spacerts.units.Order;
import spacerts.units.ShipClass;
import spacerts.units.Unit;
import spacerts.world.Difficulty;
import spacerts.world.Faction;
import spacerts.world.Galaxy;
import spacerts.world.GalaxyGenerator;
import spacerts.world.GameSetup;
import spacerts.world.World;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many ships the simulation can run inside its 33 ms tick budget (30 updates a second).
 * Not part of the normal test run: {@code gradlew :core:stressTest}. Prints a table and writes
 * {@code core/build/reports/stress/stress.csv}. Only correctness is asserted, never speed.
 */
@Tag("stress")
class StressTest {

    static final double BUDGET_MS = Lockstep.STEP * 1000.0;
    static final int[] COUNTS = {250, 500, 1000, 2000, 4000, 8000, 12000, 16000, 24000, 32000};
    static final ShipClass[] MIX = {ShipClass.LANCER, ShipClass.LANCER, ShipClass.LANCER, ShipClass.BULWARK,
            ShipClass.BULWARK, ShipClass.LONGBOW, ShipClass.LONGBOW, ShipClass.HIVE};
    static final float WARMUP_S = 2f;
    static final int MEASURED_TICKS = 300;

    static final class Result {
        String scenario;
        int ships, units;
        double avg, p95, max;
        long heapMb;
    }

    private final List<Result> results = new ArrayList<>();

    @Test
    void battle() {
        ladder("battle");
    }

    @Test
    void spreadOut() {
        ladder("spread");
    }

    private void ladder(String scenario) {
        System.out.printf("%n== %s (budget %.1f ms per update) ==%n", scenario, BUDGET_MS);
        System.out.printf("%7s %7s %9s %9s %9s %8s%n", "ships", "units", "avg ms", "p95 ms", "max ms", "heap MB");
        int fits = 0;
        for (int n : COUNTS) {
            Result r = measure(scenario, n);
            results.add(r);
            System.out.printf("%7d %7d %9.2f %9.2f %9.2f %8d%s%n", r.ships, r.units, r.avg, r.p95, r.max, r.heapMb,
                    r.avg <= BUDGET_MS ? "" : "  over budget");
            if (r.avg <= BUDGET_MS) fits = r.units;
            if (r.avg > BUDGET_MS * 2) break;
        }
        System.out.printf("%s: fits in %.0f ms up to ~%d units%n", scenario, BUDGET_MS, fits);
        writeCsv();
    }

    private static World world() {
        World w = new World(GameSetup.skirmish(4242, 1, Difficulty.NORMAL));
        GalaxyGenerator.generate(w);
        w.flushPending();
        for (Unit u : w.units) // a clean field: only the two empires
            if (u.owner.kind == Faction.Kind.NATIVES || u.owner.kind == Faction.Kind.PIRATES) u.kill(true);
        w.update(Lockstep.STEP);
        return w;
    }

    private static Faction rival(World w) {
        for (Faction f : w.factions) if (f.kind == Faction.Kind.RIVAL) return f;
        throw new IllegalStateException();
    }

    private static Unit ship(World w, ShipClass c, Faction f, float x, float y) {
        Unit u = w.spawn(c, f, x, y);
        u.hp = 1e9f; // nobody dies, so the count holds while we measure
        u.retreatOff = true;
        return u;
    }

    private Result measure(String scenario, int n) {
        World w = world();
        Faction p = w.player, r = rival(w);
        Random rng = new Random(n);
        float c = Galaxy.CLASSIC_SIZE / 2, size = Galaxy.CLASSIC_SIZE;
        List<Unit> mine = new ArrayList<>(), theirs = new ArrayList<>();
        int side = (int) Math.ceil(Math.sqrt(n / 2.0));
        for (int i = 0; i < n; i++) {
            Faction f = i % 2 == 0 ? p : r;
            ShipClass cls = MIX[(i / 2) % MIX.length];
            float x, y;
            if (scenario.equals("battle")) { // two blocks 600 apart, facing each other
                int k = i / 2;
                x = c + (f == p ? -300 : 300) + (f == p ? -1 : 1) * (k / side) * 40f;
                y = c + (k % side - side / 2f) * 40f;
            } else {
                x = size * 0.05f + rng.nextFloat() * size * 0.9f;
                y = size * 0.05f + rng.nextFloat() * size * 0.9f;
            }
            (f == p ? mine : theirs).add(ship(w, cls, f, x, y));
        }
        w.flushPending();
        for (Unit u : mine) order(u, scenario, c + 300, c, rng, size);
        for (Unit u : theirs) order(u, scenario, c - 300, c, rng, size);

        int warm = (int) (WARMUP_S / Lockstep.STEP);
        for (int i = 0; i < warm; i++) w.update(Lockstep.STEP);
        double[] ms = new double[MEASURED_TICKS];
        for (int i = 0; i < MEASURED_TICKS; i++) {
            long t0 = System.nanoTime();
            w.update(Lockstep.STEP);
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        for (Unit u : w.units)
            assertFalse(Float.isNaN(u.x) || Float.isNaN(u.y) || Float.isNaN(u.hp), "no NaN state: " + u.s.name);

        Result res = new Result();
        res.scenario = scenario;
        res.ships = n;
        res.units = (int) w.units.stream().filter(u -> !u.dead).count();
        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        res.avg = Arrays.stream(ms).average().orElse(0);
        res.p95 = sorted[(int) (sorted.length * 0.95) - 1];
        res.max = sorted[sorted.length - 1];
        Runtime rt = Runtime.getRuntime();
        res.heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return res;
    }

    private static void order(Unit u, String scenario, float x, float y, Random rng, float size) {
        if (scenario.equals("battle")) u.command(Order.ATTACK_MOVE, x, y);
        else u.command(Order.MOVE, size * 0.05f + rng.nextFloat() * size * 0.9f, size * 0.05f + rng.nextFloat() * size * 0.9f);
    }

    private void writeCsv() {
        File dir = new File("build/reports/stress");
        dir.mkdirs();
        File out = new File(dir, "stress-" + results.get(0).scenario + ".csv");
        try (PrintWriter pw = new PrintWriter(out)) {
            pw.println("scenario,ships,units,avg_ms,p95_ms,max_ms,heap_mb");
            for (Result r : results)
                pw.printf(java.util.Locale.ROOT, "%s,%d,%d,%.3f,%.3f,%.3f,%d%n", r.scenario, r.ships, r.units, r.avg, r.p95, r.max, r.heapMb);
        } catch (IOException e) {
            fail(e);
        }
    }
}

package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.SphereUnion;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure behavior of the {@code /orge} command (DESIGN observability track, Topic A). No Brigadier,
 * no Minecraft text: it takes a {@link Request} and returns a {@link Response} of plain feedback
 * lines, so it is fully headless-testable. Reads walk an ordered {@link ThermalReadSource} chain
 * (client cache -> server fallback); writes go to a single server-authoritative {@link ThermalWriteSink}.
 */
public final class OrgeCommandLogic {

    public enum Op { GET, SECTION, SET, FILL }

    /** Max cells a single fill may touch (32^3). */
    public static final int FILL_CELL_CAP = 32 * 32 * 32;

    /**
     * One parsed command invocation. For non-FILL ops the second position equals the first.
     * {@code temperatureK}/{@code massKg} are null when not supplied. {@code sourceSection} is
     * null when the source has no position. {@code maxBuildY} is exclusive.
     */
    public record Request(
            Op op,
            Identifier dimension,
            int x1, int y1, int z1,
            int x2, int y2, int z2,
            Float temperatureK,
            Float massKg,
            boolean operator,
            SubchunkKey sourceSection,
            int minBuildY,
            int maxBuildY) {}

    public record Response(boolean ok, List<String> lines) {
        public static Response ok(String line) { return new Response(true, List.of(line)); }
        public static Response ok(List<String> lines) { return new Response(true, lines); }
        public static Response fail(String line) { return new Response(false, List.of(line)); }
    }

    private final List<ThermalReadSource> readSources;
    private final ThermalWriteSink writeSink;
    private final ReadRangeProvider readRange;

    public OrgeCommandLogic(List<ThermalReadSource> readSources,
                            ThermalWriteSink writeSink,
                            ReadRangeProvider readRange) {
        this.readSources = List.copyOf(readSources);
        this.writeSink = writeSink;
        this.readRange = readRange;
    }

    public Response run(Request r) {
        return switch (r.op()) {
            case GET -> get(r);
            case SECTION -> section(r);
            case SET -> set(r);
            case FILL -> fill(r);
        };
    }

    // ----- GET -----

    private Response get(Request r) {
        if (!inBuildRange(r.y1(), r)) {
            return Response.fail(yError(r));
        }
        CellAddress addr = CellAddress.of(r.x1(), r.y1(), r.z1());
        if (!readAllowed(r, addr.key())) {
            return Response.fail(rangeError());
        }
        Optional<SectionView> v = resolve(r.dimension(), addr.key());
        if (v.isEmpty()) {
            return Response.fail(noStore(r.dimension()));
        }
        SectionView view = v.get();
        float t = view.tempAt(addr.cell());
        float m = view.massAt(addr.cell());
        String formStr = view.form() + (view.ambient() ? " (ambient)" : "");
        return Response.ok(String.format(Locale.ROOT,
                "cell (%d,%d,%d) [%s]: %.2f K (%.2f C), %.1f kg, form=%s",
                r.x1(), r.y1(), r.z1(), r.dimension(), t, t - 273.15f, m, formStr));
    }

    // ----- ops implemented in later tasks -----

    private Response section(Request r) {
        if (!inBuildRange(r.y1(), r)) {
            return Response.fail(yError(r));
        }
        SubchunkKey key = CellAddress.of(r.x1(), r.y1(), r.z1()).key();
        if (!readAllowed(r, key)) {
            return Response.fail(rangeError());
        }
        Optional<SectionView> v = resolve(r.dimension(), key);
        if (v.isEmpty()) {
            return Response.fail(noStore(r.dimension()));
        }
        SectionView view = v.get();
        float tMin = Float.POSITIVE_INFINITY, tMax = Float.NEGATIVE_INFINITY, tSum = 0f;
        float mMin = Float.POSITIVE_INFINITY, mMax = Float.NEGATIVE_INFINITY, mSum = 0f;
        float t0 = view.tempAt(0);
        int nonUniform = 0;
        int cells = net.rainbowcreation.orge.section.SectionData.CELLS;
        for (int i = 0; i < cells; i++) {
            float t = view.tempAt(i);
            float m = view.massAt(i);
            tMin = Math.min(tMin, t); tMax = Math.max(tMax, t); tSum += t;
            mMin = Math.min(mMin, m); mMax = Math.max(mMax, m); mSum += m;
            if (t != t0) {
                nonUniform++;
            }
        }
        String formStr = view.form() + (view.ambient() ? " (ambient)" : "");
        return Response.ok(List.of(
                String.format(Locale.ROOT, "section (%d,%d,%d) [%s]: form=%s",
                        key.cx(), key.sectionY(), key.cz(), r.dimension(), formStr),
                String.format(Locale.ROOT, "  T    min/avg/max = %.2f / %.2f / %.2f K",
                        tMin, tSum / cells, tMax),
                String.format(Locale.ROOT, "  mass min/avg/max = %.1f / %.1f / %.1f kg",
                        mMin, mSum / cells, mMax),
                String.format(Locale.ROOT, "  non-uniform cells (T!=cell0): %d / %d", nonUniform, cells)));
    }
    private Response set(Request r) {
        if (!r.operator()) {
            return Response.fail(opError());
        }
        if (!inBuildRange(r.y1(), r)) {
            return Response.fail(yError(r));
        }
        CellAddress addr = CellAddress.of(r.x1(), r.y1(), r.z1());
        if (!writeSink.isLoaded(r.dimension(), addr.key())) {
            return Response.fail(notLoaded());
        }
        writeSink.writeTemp(r.dimension(), addr.key(), addr.cell(), r.temperatureK());
        String massPart;
        if (r.massKg() != null) {
            writeSink.writeMass(r.dimension(), addr.key(), addr.cell(), r.massKg());
            massPart = String.format(Locale.ROOT, ", %.1f kg", r.massKg());
        } else {
            massPart = " (mass unchanged)";
        }
        return Response.ok(String.format(Locale.ROOT, "set (%d,%d,%d) -> %.2f K%s",
                r.x1(), r.y1(), r.z1(), r.temperatureK(), massPart));
    }

    private Response fill(Request r) {
        if (!r.operator()) {
            return Response.fail(opError());
        }
        int xlo = Math.min(r.x1(), r.x2()), xhi = Math.max(r.x1(), r.x2());
        int ylo = Math.min(r.y1(), r.y2()), yhi = Math.max(r.y1(), r.y2());
        int zlo = Math.min(r.z1(), r.z2()), zhi = Math.max(r.z1(), r.z2());
        long cells = (long) (xhi - xlo + 1) * (yhi - ylo + 1) * (zhi - zlo + 1);
        if (cells > FILL_CELL_CAP) {
            return Response.fail(String.format(Locale.ROOT, "fill too large: %d cells (max %d)", cells, FILL_CELL_CAP));
        }
        if (ylo < r.minBuildY() || yhi >= r.maxBuildY()) {
            return Response.fail(yError(r));
        }
        int written = 0, skipped = 0;
        for (int x = xlo; x <= xhi; x++) {
            for (int y = ylo; y <= yhi; y++) {
                for (int z = zlo; z <= zhi; z++) {
                    CellAddress addr = CellAddress.of(x, y, z);
                    if (!writeSink.isLoaded(r.dimension(), addr.key())) {
                        skipped++;
                        continue;
                    }
                    writeSink.writeTemp(r.dimension(), addr.key(), addr.cell(), r.temperatureK());
                    if (r.massKg() != null) {
                        writeSink.writeMass(r.dimension(), addr.key(), addr.cell(), r.massKg());
                    }
                    written++;
                }
            }
        }
        if (written == 0) {
            return Response.fail("fill wrote 0 cells (none loaded); move closer");
        }
        String msg = String.format(Locale.ROOT, "filled %d cells in [(%d,%d,%d)..(%d,%d,%d)] -> %.2f K",
                written, xlo, ylo, zlo, xhi, yhi, zhi, r.temperatureK());
        if (skipped > 0) {
            msg += String.format(Locale.ROOT, " (%d skipped: not loaded)", skipped);
        }
        return Response.ok(msg);
    }

    // ----- helpers -----

    /**
     * Raw read-chain access for callers that format their own per-cell output (e.g. the action-bar
     * live readout): the resolved {@link SectionView}, or empty when no store serves {@code dimension}.
     */
    public Optional<SectionView> view(Identifier dimension, SubchunkKey key) {
        return resolve(dimension, key);
    }

    private Optional<SectionView> resolve(Identifier dim, SubchunkKey key) {
        for (ThermalReadSource s : readSources) {
            Optional<SectionView> v = s.section(dim, key);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    private boolean inBuildRange(int y, Request r) {
        return y >= r.minBuildY() && y < r.maxBuildY();
    }

    private boolean readAllowed(Request r, SubchunkKey target) {
        if (r.operator()) {
            return true;
        }
        if (r.sourceSection() == null) {
            return false;
        }
        return SphereUnion.contains(r.sourceSection(), target, readRange.sectionReadRange());
    }

    private String yError(Request r) {
        return String.format(Locale.ROOT, "Y out of build height [%d,%d)", r.minBuildY(), r.maxBuildY());
    }

    private String rangeError() {
        return "out of range; you can read cells within " + readRange.sectionReadRange()
                + " sections of you (op to read anywhere)";
    }

    private static String noStore(Identifier dim) {
        return "no ORGE data for dimension " + dim;
    }

    private static String opError() {
        return "requires operator (permission level 2)";
    }

    private static String notLoaded() {
        return "target section not loaded; move closer";
    }
}

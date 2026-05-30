package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.SphereUnion;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;
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
        return Response.ok(String.format(
                "cell (%d,%d,%d) [%s]: %.2f K (%.2f C), %.1f kg, form=%s",
                r.x1(), r.y1(), r.z1(), r.dimension(), t, t - 273.15f, m, formStr));
    }

    // ----- ops implemented in later tasks -----

    private Response section(Request r) { throw new UnsupportedOperationException("Task 6"); }
    private Response set(Request r) { throw new UnsupportedOperationException("Task 7"); }
    private Response fill(Request r) { throw new UnsupportedOperationException("Task 8"); }

    // ----- helpers -----

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
        return String.format("Y out of build height [%d,%d)", r.minBuildY(), r.maxBuildY());
    }

    private String rangeError() {
        return "out of range; you can read cells within " + readRange.sectionReadRange()
                + " sections of you (op to read anywhere)";
    }

    private static String noStore(Identifier dim) {
        return "no ORGE data for dimension " + dim;
    }
}

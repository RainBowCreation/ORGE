package net.rainbowcreation.orge.section;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Test fixture: a column left on disk by a pre-composition (v5, one-material) build. */
public final class LegacyColumns {

    private LegacyColumns() {}

    /**
     * Writes {@code column} into {@code world}'s region file for chunk {@code (cx, cz)} in the v5 format: the
     * v6 layout without the composition block — every section's trailing {@code hasSlots} flag dropped. The
     * sections must be one-component (no slots), as every v5 section was.
     */
    public static void writeV5(Path world, int cx, int cz, Map<Integer, SectionData> column) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(SectionCodec.ONE_COMPONENT_VERSION);
        out.writeShort(column.size());
        for (Map.Entry<Integer, SectionData> e : new TreeMap<>(column).entrySet()) {
            if (e.getValue().hasSlots()) {
                throw new IllegalArgumentException("a v5 section has no composition slots");
            }
            ByteArrayOutputStream sec = new ByteArrayOutputStream();
            SectionCodec.writeSection(new DataOutputStream(sec), e.getValue());
            byte[] v6 = sec.toByteArray();
            out.writeInt(e.getKey());
            out.write(Arrays.copyOf(v6, v6.length - 1));   // drop the hasSlots == 0 flag
        }
        out.flush();
        Path dir = world.resolve(RegionStore.DIR_NAME);
        Files.createDirectories(dir);
        try (RegionFile rf = new RegionFile(dir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".orge"))) {
            rf.writeChunk(cx, cz, bos.toByteArray());
        }
    }
}

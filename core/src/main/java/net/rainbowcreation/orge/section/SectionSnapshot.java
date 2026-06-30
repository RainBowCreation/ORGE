package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * An immutable, fully-materialized view of one {@link SectionData} section, exchanged with
 * {@link SectionCodec} so the codec never reaches past {@code SectionData}'s interface.
 *
 * <p>This is the serialization seam: {@code SectionData} produces a snapshot of its current
 * state ({@link SectionData#snapshot()}) and is rebuilt from one ({@link SectionData#fromSnapshot}),
 * while the codec reads and writes only this typed value. The codec therefore no longer queries
 * {@code has*()} predicates, grabs the live channel arrays, or pokes into the material palette's
 * raw index array — it asks the snapshot for {@linkplain #form() form}, channel presence
 * ({@link #hasMomentum()} / {@link #hasPressure()} / {@link #hasMaterials()}) and the per-channel
 * payloads. Crucially, presence is reported by this snapshot (a {@code null} component means
 * "absent / all-zero"), so the on-disk wire shape is decoupled from {@code SectionData}'s internal
 * lazy-allocation state.</p>
 *
 * <p><b>Channel conventions</b> (mirroring {@code SectionData}'s lazy layout, byte-for-byte):
 * <ul>
 *   <li>{@code enthalpy}/{@code mass} are non-{@code null} iff {@link #isFull()} (a {@code UNIFORM}
 *       section carries its two scalars in {@link #uniformEnthalpy()}/{@link #uniformMass()} instead);</li>
 *   <li>{@code momX}/{@code momY}/{@code momZ} are non-{@code null} together iff a momentum layer exists;</li>
 *   <li>{@code pressure} is non-{@code null} iff a pressure layer exists (independent of momentum);</li>
 *   <li>{@code materialPalette}/{@code materialIndices} are non-{@code null} together iff a material
 *       layer exists (it may ride on a {@code UNIFORM} section that was demoted after materials were set).</li>
 * </ul>
 * The {@code swapReady} bookkeeping channel is deliberately absent — it is in-memory-only and never
 * serialized (law §7), so it has no place in the on-disk snapshot.</p>
 *
 * <p>Arrays are referenced, not copied: on write the snapshot aliases {@code SectionData}'s live
 * backing arrays (the codec only reads them); on read it aliases freshly-decoded arrays that
 * {@code SectionData} adopts. The component arrays must not be mutated by a holder.</p>
 */
record SectionSnapshot(
        SectionData.Form form,
        float uniformEnthalpy,
        float uniformMass,
        float[] enthalpy,
        float[] mass,
        float[] momX,
        float[] momY,
        float[] momZ,
        float[] pressure,
        List<Identifier> materialPalette,
        char[] materialIndices) {

    /** Whether this section serializes as {@link SectionData.Form#FULL} (per-cell arrays). */
    boolean isFull() {
        return form == SectionData.Form.FULL;
    }

    /** Whether a momentum layer is present (extensive p⃗ [kg·m/s]); {@code false} means all-zero. */
    boolean hasMomentum() {
        return momX != null;
    }

    /** Whether a dynamic-pressure layer is present; {@code false} means all-zero. */
    boolean hasPressure() {
        return pressure != null;
    }

    /** Whether a material identity layer is present; {@code false} means all-vacuum. */
    boolean hasMaterials() {
        return materialPalette != null;
    }
}

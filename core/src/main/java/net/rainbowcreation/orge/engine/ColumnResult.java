package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask.
 *  <p>{@code momX/momY/momZ} = EXTENSIVE momentum p [kg·m/s] (law §7) returned from the engine's
 *  {@code pxOut/pyOut/pzOut} slots — the conserved transported channel, crossing with NO conversion
 *  (mirror of the absolute-E channel); velocity {@code v = p/m} is DERIVED at display only.</p>
 *  <p>{@code enthalpy} = ABSOLUTE E [J] returned from the engine's {@code eOut} slot (law #6/§7 thermal
 *  truth carrier; {@code temperature} is the derived-Kelvin diagnostic).</p> */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature,
                           float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                           double[] enthalpy, char[] slotIx, float[] slotMass, float[] restCount) {

    // {@code restCount} = the engine's per-cell rest counter after the step (consecutive unchanged steps),
    // length CHUNK_N; persisted in memory like {@code swapReady}.

    // {@code slotIx/slotMass} = DESIGN-LAW v5 #7 composition slots 1..K−1 returned by the engine (same
    // layout as {@link ColumnTask#slotIx()}).

    // ---- Convenience constructors: each delegates DIRECTLY to the canonical 12-channel constructor
    // above, zero-filling exactly the trailing channels it omits (a zero-momentum / zero-energy resting
    // one-material cell is the correct default). No tower — no convenience constructor delegates to
    // another. ----

    /** Convenience — rest counter zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                        double[] enthalpy, char[] slotIx, float[] slotMass) {
        this(matIx, mass, temperature, momX, momY, momZ, p, swapReady, enthalpy, slotIx, slotMass,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — composition slots 1..K−1 empty (every cell a one-material cell). */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                        double[] enthalpy) {
        this(matIx, mass, temperature, momX, momY, momZ, p, swapReady, enthalpy,
             new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N],
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — enthalpy (absolute E [J]) channel zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady) {
        this(matIx, mass, temperature, momX, momY, momZ, p, swapReady,
             new double[RegionMarshaller.CHUNK_N],
             new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N],
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — swapReady + enthalpy channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ, float[] p) {
        this(matIx, mass, temperature, momX, momY, momZ, p,
             new float[RegionMarshaller.CHUNK_N], new double[RegionMarshaller.CHUNK_N],
             new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N],
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — pressure + swapReady + enthalpy channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ) {
        this(matIx, mass, temperature, momX, momY, momZ,
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N],
             new double[RegionMarshaller.CHUNK_N],
             new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N],
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — momentum + pressure + swapReady + enthalpy channels zero-filled to CHUNK_N
     *  (a zero-momentum, zero-energy resting cell is correct). */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature) {
        this(matIx, mass, temperature,
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N], new double[RegionMarshaller.CHUNK_N],
             new char[RegionMarshaller.SLOT_N], new float[RegionMarshaller.SLOT_N],
             new float[RegionMarshaller.CHUNK_N]);
    }
}

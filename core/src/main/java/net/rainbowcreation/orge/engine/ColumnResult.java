package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask.
 *  <p>{@code momX/momY/momZ} = EXTENSIVE momentum p [kg·m/s] (law §7) returned from the engine's
 *  {@code pxOut/pyOut/pzOut} slots — the conserved transported channel, crossing with NO conversion
 *  (mirror of the absolute-E channel); velocity {@code v = p/m} is DERIVED at display only.</p>
 *  <p>{@code enthalpy} = ABSOLUTE E [J] returned from the engine's {@code eOut} slot (law #6/§7 thermal
 *  truth carrier; {@code temperature} is the derived-Kelvin diagnostic).</p> */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature,
                           float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                           float[] enthalpy) {

    // ---- Convenience constructors: each delegates DIRECTLY to the canonical 9-channel constructor
    // above, zero-filling exactly the trailing channels it omits (a zero-momentum / zero-energy resting
    // cell is the correct default). No tower — no convenience constructor delegates to another. ----

    /** Convenience — enthalpy (absolute E [J]) channel zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady) {
        this(matIx, mass, temperature, momX, momY, momZ, p, swapReady,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — swapReady + enthalpy channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ, float[] p) {
        this(matIx, mass, temperature, momX, momY, momZ, p,
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — pressure + swapReady + enthalpy channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] momX, float[] momY, float[] momZ) {
        this(matIx, mass, temperature, momX, momY, momZ,
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Convenience — momentum + pressure + swapReady + enthalpy channels zero-filled to CHUNK_N
     *  (a zero-momentum, zero-energy resting cell is correct). */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature) {
        this(matIx, mass, temperature,
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N], new float[RegionMarshaller.CHUNK_N]);
    }
}

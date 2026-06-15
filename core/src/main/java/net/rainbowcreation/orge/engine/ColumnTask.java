package net.rainbowcreation.orge.engine;

/** One full-height column handed to the whole-region engine step. Arrays are length RegionMarshaller.CHUNK_N
 *  in engine index order: idx = x + 16*y + 6144*z, y in [0,384).
 *
 *  <p>{@code momX/momY/momZ} = EXTENSIVE momentum p [kg·m/s] (law §7) — the conserved transported channel.
 *  It crosses the engine boundary with NO conversion (mirror of the absolute-E channel below); velocity
 *  {@code v = p/m} is DERIVED at display only, never the stored/transported quantity. The marshaller feeds
 *  these to the engine's {@code pxIn/pyIn/pzIn} slots.</p>
 *
 *  <p>{@code enthalpy} = ABSOLUTE E [J], the law-§7 stored-EXTENSIVE thermal truth carrier (law #6 — E is
 *  the energy truth; the {@code temperature} channel is the engine's DERIVED-Kelvin diagnostic). The
 *  marshaller feeds it to the engine's {@code eIn} slot and the engine's {@code eOut} returns to
 *  {@link ColumnResult#enthalpy()}.</p> */
public record ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature,
                         float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady,
                         float[] enthalpy) {

    /** Back-compat constructor — enthalpy (absolute E [J]) channel zero-filled to CHUNK_N. */
    public ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature,
                      float[] momX, float[] momY, float[] momZ, float[] p, float[] swapReady) {
        this(cx, cz, matIx, mass, temperature, momX, momY, momZ, p, swapReady,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Back-compat constructor — swapReady + enthalpy channels zero-filled to CHUNK_N. */
    public ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature,
                      float[] momX, float[] momY, float[] momZ, float[] p) {
        this(cx, cz, matIx, mass, temperature, momX, momY, momZ, p,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Back-compat constructor — pressure channel zero-filled to CHUNK_N. */
    public ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature,
                      float[] momX, float[] momY, float[] momZ) {
        this(cx, cz, matIx, mass, temperature, momX, momY, momZ,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Back-compat constructor — momentum + pressure channels zero-filled to CHUNK_N (a zero-momentum
     *  resting cell is correct). */
    public ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature) {
        this(cx, cz, matIx, mass, temperature,
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N]);
    }
}

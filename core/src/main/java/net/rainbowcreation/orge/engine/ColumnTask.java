package net.rainbowcreation.orge.engine;

/** One full-height column handed to the whole-region engine step. Arrays are length RegionMarshaller.CHUNK_N
 *  in engine index order: idx = x + 16*y + 6144*z, y in [0,384). */
public record ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature,
                         float[] velX, float[] velY, float[] velZ) {

    /** Back-compat constructor — velocity channels zero-filled to CHUNK_N. */
    public ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature) {
        this(cx, cz, matIx, mass, temperature,
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N]);
    }
}

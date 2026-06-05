package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask. */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature,
                           float[] velX, float[] velY, float[] velZ) {

    /** Back-compat constructor — velocity channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature) {
        this(matIx, mass, temperature,
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N]);
    }
}

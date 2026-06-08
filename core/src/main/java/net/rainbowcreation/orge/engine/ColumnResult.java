package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask. */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature,
                           float[] velX, float[] velY, float[] velZ, float[] p) {

    /** Back-compat constructor — pressure channel zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] velX, float[] velY, float[] velZ) {
        this(matIx, mass, temperature, velX, velY, velZ,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Back-compat constructor — velocity + pressure channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature) {
        this(matIx, mass, temperature,
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N],
             new float[RegionMarshaller.CHUNK_N]);
    }
}

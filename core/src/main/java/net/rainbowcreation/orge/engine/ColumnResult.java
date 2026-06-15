package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask.
 *  <p>{@code enthalpy} = ABSOLUTE E [J] returned from the engine's {@code eOut} slot (law #6/§7 thermal
 *  truth carrier; {@code temperature} is the derived-Kelvin diagnostic).</p> */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature,
                           float[] velX, float[] velY, float[] velZ, float[] p, float[] swapReady,
                           float[] enthalpy) {

    /** Back-compat constructor — enthalpy (absolute E [J]) channel zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] velX, float[] velY, float[] velZ, float[] p, float[] swapReady) {
        this(matIx, mass, temperature, velX, velY, velZ, p, swapReady,
             new float[RegionMarshaller.CHUNK_N]);
    }

    /** Back-compat constructor — swapReady + enthalpy channels zero-filled to CHUNK_N. */
    public ColumnResult(char[] matIx, float[] mass, float[] temperature,
                        float[] velX, float[] velY, float[] velZ, float[] p) {
        this(matIx, mass, temperature, velX, velY, velZ, p,
             new float[RegionMarshaller.CHUNK_N]);
    }

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

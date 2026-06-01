package net.rainbowcreation.orge.engine;

/** One full-height column handed to the whole-region engine step. Arrays are length RegionMarshaller.CHUNK_N
 *  in engine index order: idx = x + 16*y + 6144*z, y in [0,384). */
public record ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature) {
}

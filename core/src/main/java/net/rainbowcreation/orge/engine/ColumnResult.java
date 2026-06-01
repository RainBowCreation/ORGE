package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask. */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature) {
}

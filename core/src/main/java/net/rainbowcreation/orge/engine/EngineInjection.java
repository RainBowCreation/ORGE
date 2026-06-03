package net.rainbowcreation.orge.engine;

/**
 * One placement injection for a single engine step (Part A of the placement-injection spec).
 *
 * @param columnId    0-based index of the target column in the {@code List<ColumnTask>} handed to
 *                    {@link OrgeEngine#stepWorld(java.util.List, java.util.List, double, int, java.util.List)}
 *                    (the {@code injColumn} the native expects — purely positional).
 * @param cellIndex   engine cell index {@code x + 16*y + 6144*z} within that column.
 * @param species     LUT index (char) of the species to place — an index into the same {@code List<Material>}
 *                    lut passed to {@code stepWorld}.
 * @param mass        kg to place (the legitimate seed, e.g. water 1000).
 * @param temperature K to place.
 */
public record EngineInjection(int columnId, int cellIndex, char species, float mass, float temperature) {
}

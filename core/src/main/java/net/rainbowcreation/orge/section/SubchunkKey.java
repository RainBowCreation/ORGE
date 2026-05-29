package net.rainbowcreation.orge.section;

/**
 * Addresses one 16×16×16 section — the simulation unit (DESIGN.md §4). Matches both
 * Minecraft's {@code ChunkSection} and the engine's section granularity.
 *
 * @param cx       chunk X
 * @param sectionY section Y (vanilla section index, not block Y)
 * @param cz       chunk Z
 */
public record SubchunkKey(int cx, int sectionY, int cz) {
}

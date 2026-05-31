package net.rainbowcreation.orge.engine;

/**
 * One section's output from a single engine step (DESIGN §10 Phase-2a): the new
 * per-cell temperatures AND per-cell mass. Mass now flows back from the engine
 * (advection), where before only temperature did.
 *
 * <p>Phase-2b adds {@code material}: the output species index per cell after any
 * in-engine phase transitions. {@code null} when the caller does not consume species
 * output (back-compat 2-arg ctor).</p>
 *
 * @param temperature K per cell (length 4096, x-fastest)
 * @param mass        kg per cell (length 4096, x-fastest) after advection
 * @param material    output material LUT index per cell (length 4096), or {@code null}
 */
public record StepResult(float[] temperature, float[] mass, char[] material) {

    /** Back-compat constructor: material is unknown / not consumed — stored as {@code null}. */
    public StepResult(float[] temperature, float[] mass) {
        this(temperature, mass, null);
    }
}

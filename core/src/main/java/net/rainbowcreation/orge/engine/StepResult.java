package net.rainbowcreation.orge.engine;

/**
 * One section's output from a single engine step (DESIGN §10 Phase-2a): the new
 * per-cell temperatures AND per-cell mass. Mass now flows back from the engine
 * (advection), where before only temperature did.
 *
 * @param temperature K per cell (length 4096, x-fastest)
 * @param mass        kg per cell (length 4096, x-fastest) after advection
 */
public record StepResult(float[] temperature, float[] mass) {}

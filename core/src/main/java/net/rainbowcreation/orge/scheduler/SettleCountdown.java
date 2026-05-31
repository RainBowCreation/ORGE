package net.rainbowcreation.orge.scheduler;

/**
 * Per-section, per-pass settle countdown (DESIGN §10 Decision 11) — the user's proven decaying-cell
 * method lifted from cell to section grain. Immutable value: each step returns a new instance, so the
 * active-set map can swap the reference without aliasing surprises (server-thread confined, no locking).
 *
 * <p>A pass (flow / thermal) is <b>dormant</b> once its countdown has reached 0 after {@link #K_SETTLE}
 * consecutive "quiet" steps (a per-step max-|Δ| below the pass threshold). A real move
 * ({@code delta >= threshold}) resets that pass's countdown to {@link #K_SETTLE}. The countdown — not
 * instant sleep — is the hysteresis that stops a near-equilibrium section flickering. Both passes
 * dormant ⇒ {@link #asleep()} ⇒ the scheduler drops the section from the schedule until a wake.</p>
 */
public record SettleCountdown(int flowCountdown, int thermalCountdown) {

    /** Consecutive quiet steps a pass must see before it sleeps (audit-tunable). */
    public static final int K_SETTLE = 3;
    /** kg; a per-step {@code max|Δmass|} at/below this is "no mass moved" (matches Plan-1 Task-6). */
    public static final float EPS_MASS = 1e-3f;
    /** K; a per-step {@code max|ΔT|} at/below this is "no heat moved". */
    public static final float EPS_TEMP = 1e-3f;

    /** A fully-active section: both passes have the full countdown remaining. */
    public static SettleCountdown active() {
        return new SettleCountdown(K_SETTLE, K_SETTLE);
    }

    /** Advection step bookkeeping: a quiet step decrements (floored at 0); a real move resets. */
    public SettleCountdown noteFlow(float maxMassDelta) {
        int fc = maxMassDelta >= EPS_MASS ? K_SETTLE : Math.max(0, flowCountdown - 1);
        return new SettleCountdown(fc, thermalCountdown);
    }

    /** Conduction step bookkeeping: a quiet step decrements (floored at 0); a real move resets. */
    public SettleCountdown noteThermal(float maxTempDelta) {
        int tc = maxTempDelta >= EPS_TEMP ? K_SETTLE : Math.max(0, thermalCountdown - 1);
        return new SettleCountdown(flowCountdown, tc);
    }

    /** Explicit wake of the flow pass (block edit, seam flux): restore the flow countdown. */
    public SettleCountdown wakeFlow() {
        return new SettleCountdown(K_SETTLE, thermalCountdown);
    }

    /** Explicit wake of the thermal pass (source-roster change): restore the thermal countdown. */
    public SettleCountdown wakeThermal() {
        return new SettleCountdown(flowCountdown, K_SETTLE);
    }

    /** Explicit wake of both passes (a generic edit that can affect mass AND heat). */
    public SettleCountdown wakeAll() {
        return active();
    }

    public boolean flowDormant() {
        return flowCountdown <= 0;
    }

    public boolean thermalDormant() {
        return thermalCountdown <= 0;
    }

    /** Both passes dormant — drop the section from the schedule entirely until a wake. */
    public boolean asleep() {
        return flowDormant() && thermalDormant();
    }
}

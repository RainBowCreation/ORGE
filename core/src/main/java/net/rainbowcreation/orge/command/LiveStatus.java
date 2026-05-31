package net.rainbowcreation.orge.command;

/**
 * Lifecycle status of a section, for the {@code /orge get-live} action-bar readout. This is the
 * load/simulation state an operator cares about — distinct from {@link net.rainbowcreation.orge.section.SectionData.Form}
 * (UNIFORM/FULL), which is only the in-memory array packing.
 */
public enum LiveStatus {
    /** The chunk column is not held in memory; the shown T/mass is the synthesized ambient baseline. */
    UNLOADED,
    /** Column loaded, but this section has never been simulated — it sits at the ambient baseline. */
    AMBIENT,
    /** Section is in the simulation schedule and stepping each tick. */
    ACTIVE,
    /** Section has settled and been dropped from the schedule until a wake (block edit, seam flux, …). */
    DORMANT
}

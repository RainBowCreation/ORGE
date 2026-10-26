package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.rainbowcreation.orge.section.SectionData;

import java.util.List;

/**
 * One column as the server thread captured it for a cycle (issue #51): per section a copy of the live
 * block states, the store section and the recorded engine-output species, plus the column's queued
 * placement intents. The snapshot assembly ({@link ColumnSnapshot}) and the write-back preparation
 * ({@link ColumnWriteBack}) read and write only this copy, off the server thread; nothing in it is
 * shared with live world or store state. Opaque outside the scheduler package.
 */
public final class CapturedColumn {

    /**
     * One of the column's 24 sections, captured.
     *
     * @param blocks   copy of the chunk section's block states, or null: the chunk has no section at this Y
     * @param ambientK biome ambient temperature at the section centre [K]
     * @param prior    last cycle's recorded engine-output species ({@link CellMaterialTracker}), or null;
     *                 never mutated once recorded, so it is held by reference
     * @param data     deep copy of what the store reads for this key ({@code store.get}: the stored section,
     *                 or the ambient one it materializes), or null when the column has no loaded store. The
     *                 worker owns it: the assembly reads it, then the write-back preparation turns it into
     *                 the section it installs
     * @param stored   the store held the section (it had been simulated or edited)
     */
    record Section(PalettedContainer<BlockState> blocks, float ambientK, Identifier[] prior, SectionData data,
                   boolean stored) {

        /** The block state at section cell {@code i} (x-fastest, {@code x + 16y + 256z}). */
        BlockState blockAt(int i) {
            return blocks.get(i & 15, (i >> 4) & 15, (i >> 8) & 15);
        }
    }

    final Identifier dimension;
    final int cx;
    final int cz;
    /** Index {@code sectionY - ColumnAssembler.MIN_SECTION_Y}. */
    final Section[] sections;
    /** The column's placement intents queued at capture time. */
    final List<PendingInjections.Intent> intents;
    /** The column's share of the sky pass that ran with this capture (step thread), or null: none ran. */
    MinecraftThermalWorld.SkyLedgers sky;

    CapturedColumn(Identifier dimension, int cx, int cz, Section[] sections, List<PendingInjections.Intent> intents) {
        this.dimension = dimension;
        this.cx = cx;
        this.cz = cz;
        this.sections = sections;
        this.intents = intents;
    }

    Section section(int sectionY) {
        return sections[sectionY - ColumnAssembler.MIN_SECTION_Y];
    }
}

package net.rainbowcreation.orge;

import com.tterrag.registrate.Registrate;
import com.tterrag.registrate.util.entry.RegistryEntry;
import net.minecraft.world.level.block.Blocks;
import net.rainbowcreation.orge.content.element.solid.dirt.DirtBlock;

public class AllBlocks {
    private static final Registrate REGISTRATE = Orge.registrate();
    /*
    public static final RegistryEntry<DirtBlock> DIRT = REGISTRATE.block("dirt", DirtBlock::new)
            .initialProperties(() -> Blocks.DIRT)
            .simpleItem()
            .register();
     */

    // loader
    public static void register() {}
}

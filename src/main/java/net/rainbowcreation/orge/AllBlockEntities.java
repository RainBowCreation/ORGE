package net.rainbowcreation.orge;

import com.tterrag.registrate.Registrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import net.rainbowcreation.orge.content.element.solid.dirt.DirtBlockEntity;

public class AllBlockEntities {
    private static final Registrate REGISTRATE = Orge.registrate();

    /*
    public static final BlockEntityEntry<DirtBlockEntity> DIRT = REGISTRATE.blockEntity("dirt", DirtBlockEntity::new)
            .validBlock(AllBlocks.DIRT)
            .register();
     */

    public static void register() {}
}

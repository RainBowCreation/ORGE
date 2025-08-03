package net.rainbowcreation.orge.content.element.solid.dirt;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.rainbowcreation.orge.AllBlockEntities;
import net.rainbowcreation.orge.foundation.block.IBE;

public class DirtBlock extends Block implements IBE<DirtBlockEntity> {
    public DirtBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<DirtBlockEntity> getBlockEntityClass() {
        return DirtBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DirtBlockEntity> getBlockEntityType() {
        return null;//AllBlockEntities.DIRT.get();
    }
}

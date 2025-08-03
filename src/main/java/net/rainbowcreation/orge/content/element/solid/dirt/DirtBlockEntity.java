package net.rainbowcreation.orge.content.element.solid.dirt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.rainbowcreation.orge.Orge;
import net.rainbowcreation.orge.foundation.blockEntity.OrgeBlockEntity;
import net.rainbowcreation.orge.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import org.slf4j.Logger;

import java.util.List;

public class DirtBlockEntity extends OrgeBlockEntity {
    private static final Logger LOGGER = Orge.LOGGER;
    public DirtBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void initialize() {
        super.initialize();
        setLazyTickRate(20);
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        BlockPos blockPos = getBlockPos();
        Level level = getLevel();
        if (level == null) return;

        BlockPos abovePos = blockPos.above();
        if (level.getBlockState(abovePos).isAir()) {
            // Check for players in the block space above
            AABB searchBox = new AABB(
                    abovePos.getX(), abovePos.getY(), abovePos.getZ(),
                    abovePos.getX() + 1.0, abovePos.getY() + 1.0, abovePos.getZ() + 1.0
            );
            List<Player> players = level.getEntitiesOfClass(Player.class, searchBox);

            if (!players.isEmpty()) {
                LOGGER.info("Player {} detected above dirt block at {}",
                        players.get(0).getName().getString(),
                        blockPos.toShortString());
            }
        }
    }
}

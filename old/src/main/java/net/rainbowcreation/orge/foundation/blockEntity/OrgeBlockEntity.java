package net.rainbowcreation.orge.foundation.blockEntity;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.api.event.BlockEntityBehaviourEvent;
import net.rainbowcreation.orge.foundation.blockEntity.behaviour.BehaviourType;
import net.rainbowcreation.orge.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public abstract class OrgeBlockEntity extends BlockEntity {
    private final Map<BehaviourType<?>, BlockEntityBehaviour> behaviours = new Reference2ObjectArrayMap<>();
    private boolean initialized = false;
    private boolean firstNbtRead = true;
    protected int lazyTickRate;
    protected int lazyTickCounter;
    private boolean chunkUnloaded;

    // Used for simulating this BE in a client-only setting
    private boolean virtualMode;

	public OrgeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);

        setLazyTickRate(10);

        ArrayList<BlockEntityBehaviour> list = new ArrayList<>();
        addBehaviours(list);
        list.forEach(b -> behaviours.put(b.getType(), b));
    }

    public abstract void addBehaviours(List<BlockEntityBehaviour> behaviours);

    /**
     * Gets called just before reading block entity data for behaviours. Register
     * anything here that depends on your custom BE data.
     */
    public void addBehavioursDeferred(List<BlockEntityBehaviour> behaviours) {}

    public void initialize() {
        if (firstNbtRead) {
            firstNbtRead = false;
            BlockEntityBehaviourEvent.EVENT.invoker().manageBehaviors(new BlockEntityBehaviourEvent(this, behaviours));
        }

        forEachBehaviour(BlockEntityBehaviour::initialize);
        lazyTick();
    }

    public void tick() {
        if (hasLevel()) {
            if (level.isClientSide)
                return;
            if (!initialized) {
                initialize();
                initialized = true;
            }
        }

        if (lazyTickCounter-- <= 0) {
            lazyTickCounter = lazyTickRate;
            lazyTick();
        }

        forEachBehaviour(BlockEntityBehaviour::tick);
    }

    public void lazyTick() {}

    /**
     * Hook only these in future subclasses of STE
     */
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.saveAdditional(tag);
        forEachBehaviour(tb -> tb.write(tag, clientPacket));
    }

    /**
     * Hook only these in future subclasses of STE
     */
    protected void read(CompoundTag tag, boolean clientPacket) {
        if (firstNbtRead) {
            firstNbtRead = false;
            ArrayList<BlockEntityBehaviour> list = new ArrayList<>();
            addBehavioursDeferred(list);
            list.forEach(b -> behaviours.put(b.getType(), b));
            BlockEntityBehaviourEvent.EVENT.invoker().manageBehaviors(new BlockEntityBehaviourEvent(this, behaviours));
        }
        super.load(tag);
        forEachBehaviour(tb -> tb.read(tag, clientPacket));
    }

    @Override
    public final void load(CompoundTag tag) {
        read(tag, false);
    }

    @Override
    public final void setRemoved() {
        super.setRemoved();
        if (!chunkUnloaded)
            remove();
        invalidate();
    }

    /**
     * Block destroyed or Chunk unloaded. Usually invalidates capabilities
     */
    public void invalidate() {
        forEachBehaviour(BlockEntityBehaviour::unload);
    }

    /**
     * Block destroyed or picked up by a contraption. Usually detaches kinetics
     */
    public void remove() {}

    /**
     * Block destroyed or replaced. Requires Block to call IBE::onRemove
     */
    public void destroy() {
        forEachBehaviour(BlockEntityBehaviour::destroy);
    }

    @Override
    public final void saveAdditional(CompoundTag tag) {
        write(tag, false);
    }

    @SuppressWarnings("unchecked")
    public <T extends BlockEntityBehaviour> T getBehaviour(BehaviourType<T> type) {
        return (T) behaviours.get(type);
    }

    public void forEachBehaviour(Consumer<BlockEntityBehaviour> action) {
        getAllBehaviours().forEach(action);
    }

    public Collection<BlockEntityBehaviour> getAllBehaviours() {
        return behaviours.values();
    }

    public void attachBehaviourLate(BlockEntityBehaviour behaviour) {
        behaviours.put(behaviour.getType(), behaviour);
        behaviour.blockEntity = this;
        behaviour.initialize();
    }

    public void removeBehaviour(BehaviourType<?> type) {
        BlockEntityBehaviour remove = behaviours.remove(type);
        if (remove != null) {
            remove.unload();
        }
    }

    public void setLazyTickRate(int slowTickRate) {
        this.lazyTickRate = slowTickRate;
        this.lazyTickCounter = slowTickRate;
    }

    public boolean isChunkUnloaded() {
        return chunkUnloaded;
    }

    public void sendToMenu(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(getBlockPos());
        buffer.writeNbt(getUpdateTag());
    }

    @SuppressWarnings("deprecation")
    public void refreshBlockState() {
        setBlockState(getLevel().getBlockState(getBlockPos()));
    }
}
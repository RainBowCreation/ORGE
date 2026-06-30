package net.rainbowcreation.orge.command;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.scheduler.LiveMaterials;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The per-player live-readout machine for {@code /orge get-live}. OWNS the toggle state (which players
 * have the readout on), the per-tick crosshair raycast, and the action-bar render — and the single
 * cell→display-material resolution shared by the live render and the {@code /orge get} suffix. The
 * {@link OrgeCommands} dispatcher delegates here so it stays a thin Brigadier adapter.
 *
 * <p>Server-thread confined: the toggle command, the tick hook ({@link #tick}) and the stop hook
 * ({@link #clear}) all run on the server thread, so a plain {@link HashSet} suffices.</p>
 */
public final class LiveReadoutManager {

    /** Max distance the {@code get-live} crosshair ray travels before giving up. */
    private static final double LIVE_REACH = 64.0;

    private final OrgeCommandLogic logic;

    /** Resolves a section's load/sim {@link LiveStatus} for the live readout line. */
    private final SectionStatusSource status;

    /**
     * Players with the readout toggled on. Each server tick their crosshair cell is painted to the
     * ACTION BAR (not chat) by {@link #tick}. Offline toggled players are kept so the readout resumes
     * on rejoin.
     */
    private final Set<UUID> liveReadout = new HashSet<>();

    public LiveReadoutManager(OrgeCommandLogic logic, SectionStatusSource status) {
        this.logic = logic;
        this.status = status;
    }

    /**
     * Flip the readout for {@code player} and report the new state. {@code true} = now ON (the next
     * {@link #tick} will start painting their crosshair cell to the action bar); {@code false} = now OFF.
     */
    public boolean toggle(ServerPlayer player) {
        final boolean nowOn = !liveReadout.remove(player.getUUID());
        if (nowOn) {
            liveReadout.add(player.getUUID());
        }
        return nowOn;
    }

    /**
     * Server-tick hook: for every player with the live readout on, paint their crosshair cell to the
     * action bar. Offline toggled players are skipped (kept so the readout resumes on rejoin).
     */
    public void tick(MinecraftServer server) {
        if (liveReadout.isEmpty()) {
            return;
        }
        for (UUID id : liveReadout) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            ServerLevel level = (ServerLevel) player.level();
            BlockHitResult hit = crosshairHit(player, level);
            String text = hit.getType() == HitResult.Type.MISS ? "—" : liveLine(level, hit.getBlockPos());
            player.displayClientMessage(Component.literal(text), true); // true = action bar
        }
    }

    /** Drops all live-readout toggles (server stop). */
    public void clear() {
        liveReadout.clear();
    }

    /**
     * {@code ", block=<id>, material=<id>"} suffix for the cell at {@code pos} — appended to the
     * {@code /orge get} line (server-thread read). Shares {@link #cellMaterial} with the live readout,
     * so both readouts report the same per-cell species.
     */
    public String cellDescriptor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Identifier matId = cellMaterial(level, pos, state);
        return String.format(Locale.ROOT, ", block=%s, material=%s", blockId, matId);
    }

    /** Raycast from {@code player}'s eyes along their look vector, fluids included (water/lava hittable). */
    private static BlockHitResult crosshairHit(ServerPlayer player, ServerLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(LIVE_REACH));
        return level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
    }

    /**
     * The short live-readout line for one cell:
     * {@code "<block>, <material>, <temp_k>:<mass>, <status>, <form>"}
     * (e.g. {@code "minecraft:water, orge:water, 288.00:1000.0, DORMANT, FULL"}). {@code status} is the
     * load/sim lifecycle ({@link LiveStatus}); {@code form} is the internal array packing. No
     * coords/dimension/labels — it rides the action bar, so it stays terse.
     */
    private String liveLine(ServerLevel level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Identifier matId = cellMaterial(level, p, state);
        CellAddress addr = CellAddress.of(p.getX(), p.getY(), p.getZ());
        Identifier dim = level.dimension().identifier();
        LiveStatus st = status.statusOf(dim, addr.key());
        Optional<SectionView> v = logic.view(dim, addr.key());
        if (v.isEmpty()) {
            return String.format(Locale.ROOT, "%s, %s, (no data), %s", blockId, matId, st);
        }
        SectionView view = v.get();
        return String.format(Locale.ROOT, "%s, %s, %.2f:%.1f, %s, %s",
                blockId, matId, view.tempAt(addr.cell()), view.massAt(addr.cell()), st, view.form());
    }

    /**
     * The material to display for a cell: the STORED per-cell species when the section is simulated
     * (authoritative sim truth), else the live block's first-touch mapping (never-simulated/ambient,
     * where the store holds only the synthesized baseline). One source of truth for both readouts.
     */
    private Identifier cellMaterial(ServerLevel level, BlockPos pos, BlockState state) {
        CellAddress addr = CellAddress.of(pos.getX(), pos.getY(), pos.getZ());
        Optional<SectionView> v = logic.view(level.dimension().identifier(), addr.key());
        if (v.isPresent() && !v.get().ambient()) {
            return v.get().material(addr.cell());
        }
        return LiveMaterials.materialFor(state.getBlock(), ActiveMaterials.current().registry()).id();
    }
}

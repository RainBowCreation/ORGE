package net.rainbowcreation.orge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.scheduler.InjectDebug;
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thin Brigadier adapter for {@code /orge}. Parses arguments, builds an
 * {@link OrgeCommandLogic.Request}, runs the pure logic, and prints the {@link OrgeCommandLogic.Response}
 * lines. Read subcommands (get/section) are open; write subcommands (set/fill) require op (level 2).
 * Register from {@code Orge.init} via Architectury's common {@code CommandRegistrationEvent}.
 */
public final class OrgeCommands {

    private final OrgeCommandLogic logic;

    /** Resolves a section's load/sim {@link LiveStatus} for the get-live readout. */
    private final SectionStatusSource status;

    /**
     * Players with {@code /orge get-live} toggled on. Each server tick their crosshair cell is
     * painted to the ACTION BAR (not chat) by {@link #tickLiveReadouts}. Server-thread confined
     * (commands + the tick hook both run on the server thread), so a plain {@link HashSet} suffices.
     */
    private final Set<UUID> liveReadout = new HashSet<>();

    public OrgeCommands(OrgeCommandLogic logic, SectionStatusSource status) {
        this.logic = logic;
        this.status = status;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("orge")
                .then(Commands.literal("get")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> read(ctx, OrgeCommandLogic.Op.GET,
                                        BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("get-live")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                        .executes(this::getLive))
                .then(Commands.literal("section")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> read(ctx, OrgeCommandLogic.Op.SECTION,
                                        BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("k", FloatArgumentType.floatArg())
                                        .executes(ctx -> set(ctx, null))
                                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0f))
                                                .executes(ctx -> set(ctx, FloatArgumentType.getFloat(ctx, "mass")))))))
                .then(Commands.literal("fill")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("k", FloatArgumentType.floatArg())
                                                .executes(ctx -> fill(ctx, null))
                                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0f))
                                                        .executes(ctx -> fill(ctx, FloatArgumentType.getFloat(ctx, "mass"))))))))
                .then(Commands.literal("debug")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                        .executes(ctx -> debug(ctx, null))
                        .then(Commands.literal("on").executes(ctx -> debug(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> debug(ctx, false)))));
    }

    /**
     * {@code /orge debug [on|off]} (op): toggles the ORGE-INJECT diagnostic log at runtime by
     * writing {@link InjectDebug#ON}. With no argument it just reports the current state. The
     * JVM flag {@code -Dorge.debug.inject} still sets the startup default.
     */
    private int debug(CommandContext<CommandSourceStack> ctx, Boolean on) {
        if (on != null) {
            InjectDebug.ON = on;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "ORGE debug log " + (InjectDebug.on() ? "ON" : "OFF")
                        + (on == null ? " (use /orge debug on|off to change)" : "")), false);
        return Command.SINGLE_SUCCESS;
    }

    private int read(CommandContext<CommandSourceStack> ctx, OrgeCommandLogic.Op op, BlockPos p) {
        OrgeCommandLogic.Response resp = logic.run(request(ctx, op, p, p, null, null));
        // GET reports one cell: append the LIVE block + the cell's material. For a SIMULATED cell the
        // material is the STORED per-cell species (sim truth — e.g. orge:vacuum after a break, even
        // though the live block is minecraft:air); for a never-simulated/ambient cell the store has no
        // real species so we fall back to the live block's first-touch mapping.
        if (op == OrgeCommandLogic.Op.GET && resp.ok()) {
            resp = appendToFirstLine(resp, liveCellDescriptor(ctx.getSource().getLevel(), p));
        }
        return print(ctx, resp);
    }

    /** Max distance the {@code get-live} crosshair ray travels before giving up. */
    private static final double LIVE_REACH = 64.0;

    /**
     * {@code /orge get-live} (op): TOGGLES a live per-cell readout for the caller. While on, each
     * server tick {@link #tickLiveReadouts} raycasts their crosshair and paints the same GET line
     * to the ACTION BAR — never chat, so it updates in place instead of flooding the log. Requires
     * a player source (it needs a crosshair).
     */
    private int getLive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("get-live needs a player (it reads your crosshair)"));
            return 0;
        }
        final boolean nowOn = !liveReadout.remove(player.getUUID());
        if (nowOn) {
            liveReadout.add(player.getUUID());
        }
        src.sendSuccess(() -> Component.literal(
                "ORGE live readout " + (nowOn ? "ON (crosshair → action bar)" : "OFF")), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Server-tick hook: for every player with the live readout on, paint their crosshair cell to
     * the action bar. Offline toggled players are skipped (kept so the readout resumes on rejoin).
     */
    public void tickLiveReadouts(MinecraftServer server) {
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
    public void clearLiveReadouts() {
        liveReadout.clear();
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

    private static SubchunkKey sectionOf(Vec3 pos) {
        return new SubchunkKey(
                ((int) Math.floor(pos.x)) >> 4,
                ((int) Math.floor(pos.y)) >> 4,
                ((int) Math.floor(pos.z)) >> 4);
    }

    /**
     * {@code ", block=<id>, material=<id>"} for the cell at {@code pos} (server-thread read). The
     * material is the STORED per-cell species for a simulated cell (so a broken cell reads its real
     * {@code orge:vacuum}, not the {@code minecraft:air} block's first-touch {@code orge:air}); for a
     * never-simulated/ambient cell, where the store has no real species, it falls back to the live
     * block's mapping.
     */
    private String liveCellDescriptor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Identifier matId = cellMaterial(level, pos, state);
        return String.format(Locale.ROOT, ", block=%s, material=%s", blockId, matId);
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

    private static OrgeCommandLogic.Response appendToFirstLine(OrgeCommandLogic.Response resp, String suffix) {
        List<String> lines = new ArrayList<>(resp.lines());
        if (!lines.isEmpty()) {
            lines.set(0, lines.get(0) + suffix);
        }
        return new OrgeCommandLogic.Response(resp.ok(), lines);
    }

    private int set(CommandContext<CommandSourceStack> ctx, Float mass) {
        BlockPos p = BlockPosArgument.getBlockPos(ctx, "pos");
        float k = FloatArgumentType.getFloat(ctx, "k");
        return dispatch(ctx, request(ctx, OrgeCommandLogic.Op.SET, p, p, k, mass));
    }

    private int fill(CommandContext<CommandSourceStack> ctx, Float mass) {
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        float k = FloatArgumentType.getFloat(ctx, "k");
        return dispatch(ctx, request(ctx, OrgeCommandLogic.Op.FILL, from, to, k, mass));
    }

    private OrgeCommandLogic.Request request(CommandContext<CommandSourceStack> ctx,
                                             OrgeCommandLogic.Op op, BlockPos p1, BlockPos p2,
                                             Float k, Float mass) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Identifier dim = level.dimension().identifier();
        SubchunkKey sourceSection = sectionOf(src.getPosition());
        boolean operator = src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
        int minY = level.getMinY();
        int maxYExclusive = level.getMaxY() + 1; // getMaxY() is inclusive top block Y
        return new OrgeCommandLogic.Request(op, dim,
                p1.getX(), p1.getY(), p1.getZ(),
                p2.getX(), p2.getY(), p2.getZ(),
                k, mass, operator, sourceSection, minY, maxYExclusive);
    }

    private int dispatch(CommandContext<CommandSourceStack> ctx, OrgeCommandLogic.Request req) {
        return print(ctx, logic.run(req));
    }

    private int print(CommandContext<CommandSourceStack> ctx, OrgeCommandLogic.Response resp) {
        CommandSourceStack src = ctx.getSource();
        for (String line : resp.lines()) {
            if (resp.ok()) {
                src.sendSuccess(() -> Component.literal(line), false);
            } else {
                src.sendFailure(Component.literal(line));
            }
        }
        return resp.ok() ? Command.SINGLE_SUCCESS : 0;
    }
}

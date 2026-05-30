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
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thin Brigadier adapter for {@code /orge}. Parses arguments, builds an
 * {@link OrgeCommandLogic.Request}, runs the pure logic, and prints the {@link OrgeCommandLogic.Response}
 * lines. Read subcommands (get/section) are open; write subcommands (set/fill) require op (level 2).
 * Register from {@code Orge.init} via Architectury's common {@code CommandRegistrationEvent}.
 */
public final class OrgeCommands {

    private final OrgeCommandLogic logic;

    public OrgeCommands(OrgeCommandLogic logic) {
        this.logic = logic;
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
                                                        .executes(ctx -> fill(ctx, FloatArgumentType.getFloat(ctx, "mass")))))))));
    }

    private int read(CommandContext<CommandSourceStack> ctx, OrgeCommandLogic.Op op, BlockPos p) {
        OrgeCommandLogic.Response resp = logic.run(request(ctx, op, p, p, null, null));
        // GET reports one cell: append the LIVE block + mapped ORGE material so the operator can
        // see what the thermal store's mass/temp actually belongs to (the store keeps no material).
        if (op == OrgeCommandLogic.Op.GET && resp.ok()) {
            resp = appendToFirstLine(resp, liveCellDescriptor(ctx.getSource().getLevel(), p));
        }
        return print(ctx, resp);
    }

    /** Max distance the {@code get-live} crosshair ray travels before giving up. */
    private static final double LIVE_REACH = 64.0;

    /**
     * {@code /orge get-live} (op): raycast from the caller's eyes along their look vector (fluids
     * included, so water/lava cells are hittable) and run the same per-cell GET on the targeted
     * block — no coordinates to type. Requires a player source (it needs a crosshair).
     */
    private int getLive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("get-live needs a player (it reads your crosshair)"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(LIVE_REACH));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() == HitResult.Type.MISS) {
            src.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "not looking at a block within %d blocks", (int) LIVE_REACH)));
            return 0;
        }
        return read(ctx, OrgeCommandLogic.Op.GET, hit.getBlockPos());
    }

    /** {@code ", block=<id>, material=<id>"} for the live block at {@code pos} (server-thread read). */
    private static String liveCellDescriptor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Material material = LiveMaterials.materialFor(state, ActiveMaterials.current());
        return String.format(Locale.ROOT, ", block=%s, material=%s", blockId, material.id());
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
        Vec3 sp = src.getPosition();
        SubchunkKey sourceSection = new SubchunkKey(
                ((int) Math.floor(sp.x)) >> 4,
                ((int) Math.floor(sp.y)) >> 4,
                ((int) Math.floor(sp.z)) >> 4);
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

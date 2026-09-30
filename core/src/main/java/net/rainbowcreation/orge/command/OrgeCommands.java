package net.rainbowcreation.orge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.Vec3;
import net.rainbowcreation.orge.scheduler.InjectDebug;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.StepMetrics;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin Brigadier adapter for {@code /orge}. Parses arguments, builds an
 * {@link OrgeCommandLogic.Request}, runs the pure logic, and prints the {@link OrgeCommandLogic.Response}
 * lines. The per-player live readout is delegated to {@link LiveReadoutManager}. Read subcommands
 * (get/section) are open; write subcommands (set/fill) require op (level 2). Register from
 * {@code Orge.init} via Architectury's common {@code CommandRegistrationEvent}.
 */
public final class OrgeCommands {

    private final OrgeCommandLogic logic;

    /** Owns the {@code /orge get-live} toggle state, raycast and action-bar render; the dispatcher only delegates. */
    private final LiveReadoutManager live;

    /** The live scheduler whose step cadence/dt {@code /orge step} reads and tunes. */
    private final Scheduler scheduler;

    public OrgeCommands(OrgeCommandLogic logic, LiveReadoutManager live, Scheduler scheduler) {
        this.logic = logic;
        this.live = live;
        this.scheduler = scheduler;
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
                        .then(Commands.literal("off").executes(ctx -> debug(ctx, false))))
                .then(Commands.literal("perf")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                        .executes(ctx -> perf(ctx, false))
                        .then(Commands.literal("reset").executes(ctx -> perf(ctx, true))))
                .then(Commands.literal("step")
                        .requires(Commands.hasPermission(new PermissionCheck.Require(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                        .executes(this::step)
                        .then(Commands.literal("ticks")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> {
                                            scheduler.setTicksPerStep(IntegerArgumentType.getInteger(ctx, "n"));
                                            return step(ctx);
                                        })))
                        .then(Commands.literal("dt")
                                .then(Commands.literal("auto").executes(ctx -> {
                                    scheduler.setFixedDt(0);
                                    return step(ctx);
                                }))
                                .then(Commands.argument("seconds", FloatArgumentType.floatArg(0.01f, 2.0f))
                                        .executes(ctx -> {
                                            // via decimal string: widening 0.01f to double gives 0.0099999… (< the 0.01 floor)
                                            scheduler.setFixedDt(Double.parseDouble(Float.toString(FloatArgumentType.getFloat(ctx, "seconds"))));
                                            return step(ctx);
                                        })))));
    }

    /**
     * {@code /orge perf [reset]} (op): does the engine keep up with real time? Prints native/server step
     * cost, latency vs the step interval budget, step counters, sim/real ratio, range and a verdict.
     */
    private int perf(CommandContext<CommandSourceStack> ctx, boolean reset) {
        StepMetrics m = scheduler.metrics();
        if (reset) m.reset();
        int ticks = scheduler.ticksPerStep();
        double budgetMs = ticks * 50.0;
        double timeScale = scheduler.onPaceDt() / (ticks / 20.0);
        String[] lines = {
                String.format("ORGE perf%s: native %.1f ms (ema %.1f, max %.1f) | server %.1f ms (ema %.1f)",
                        reset ? " (reset)" : "", m.nativeLastMs, m.nativeEmaMs, m.nativeMaxMs, m.serverLastMs, m.serverEmaMs),
                String.format("latency %d ticks (ema %.1f) | budget %.0f ms/step (%d ticks) | range %d",
                        m.latencyLastTicks, m.latencyEmaTicks, budgetMs, ticks, scheduler.range()),
                String.format("steps %d completed, %d late, %d cancelled, %d held | sim/real %.3f (%.1f s / %.1f s)",
                        m.completed, m.late, m.cancelled, m.held, m.ratio(), m.simSeconds, m.realTicks / 20.0),
                m.keepingUp(budgetMs, timeScale) ? "verdict: keeping up"
                        : "verdict: falling behind — try /orge step ticks <larger> or lower range"
        };
        for (String line : lines) ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /orge step [ticks <n> | dt <seconds|auto>]} (op): reports (after any change) the runtime
     * step cadence, dt mode and resulting time-scale = dt / (ticksPerStep/20). Runtime-only; resets on restart.
     */
    private int step(CommandContext<CommandSourceStack> ctx) {
        int ticks = scheduler.ticksPerStep();
        double dt = scheduler.onPaceDt();
        String line = String.format("ORGE step: every %d ticks, dt %s %.3f s, time-scale %.3fx",
                ticks, scheduler.fixedDt() > 0 ? "fixed" : "auto →", dt, dt / (ticks / 20.0));
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
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
            resp = appendToFirstLine(resp, live.cellDescriptor(ctx.getSource().getLevel(), p));
        }
        return print(ctx, resp);
    }

    /**
     * {@code /orge get-live} (op): TOGGLES the live per-cell readout for the caller (state owned by
     * {@link LiveReadoutManager}). While on, each server tick the manager raycasts their crosshair and
     * paints the GET line to the ACTION BAR. Requires a player source (it needs a crosshair).
     */
    private int getLive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("get-live needs a player (it reads your crosshair)"));
            return 0;
        }
        final boolean nowOn = live.toggle(player);
        src.sendSuccess(() -> Component.literal(
                "ORGE live readout " + (nowOn ? "ON (crosshair → action bar)" : "OFF")), false);
        return Command.SINGLE_SUCCESS;
    }

    private static SubchunkKey sectionOf(Vec3 pos) {
        return new SubchunkKey(
                ((int) Math.floor(pos.x)) >> 4,
                ((int) Math.floor(pos.y)) >> 4,
                ((int) Math.floor(pos.z)) >> 4);
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

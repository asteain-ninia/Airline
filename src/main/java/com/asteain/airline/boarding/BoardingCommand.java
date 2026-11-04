package com.asteain.airline.boarding;

import com.asteain.airline.boarding.Boarding.Result;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The operator command for trying boarding out by hand:
 *
 *   /airline board &lt;citizen&gt; &lt;seat&gt;       send a citizen to a seat; seat = where the block to sit on is in the world right now
 *   /airline leave &lt;citizen&gt; [&lt;ground&gt;]   have it stand up, and walk to a place on the ground when one is given
 *   /airline boarding &lt;citizen&gt;           say what the citizen is doing
 *
 * Only vanilla argument types are used, and the answers are plain text: players who join do not have this mod, so
 * nothing here may need anything on their side. Registered with or without Sable; without it the command says so.
 */
public final class BoardingCommand {
    private BoardingCommand() {}

    public static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("airline")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("board")
                        .then(Commands.argument("citizen", EntityArgument.entity())
                                .then(Commands.argument("seat", BlockPosArgument.blockPos())
                                        .executes(BoardingCommand::board))))
                .then(Commands.literal("leave")
                        .then(Commands.argument("citizen", EntityArgument.entity())
                                .executes(context -> leave(context, false))
                                .then(Commands.argument("ground", BlockPosArgument.blockPos())
                                        .executes(context -> leave(context, true)))))
                .then(Commands.literal("boarding")
                        .then(Commands.argument("citizen", EntityArgument.entity())
                                .executes(BoardingCommand::status))));
    }

    /** Checked before anything else, so that the answer does not depend on the arguments. */
    private static boolean unavailable(CommandContext<CommandSourceStack> context) {
        if (Boarding.available()) return false;
        context.getSource().sendFailure(Component.literal(Result.NO_SABLE.text));
        return true;
    }

    private static int board(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (unavailable(context)) return 0;
        final Entity entity = EntityArgument.getEntity(context, "citizen");
        final BlockPos seat = BlockPosArgument.getBlockPos(context, "seat");
        final Result result = entity instanceof Mob mob ? Boarding.boardAt(mob, Vec3.atCenterOf(seat)) : Result.NOT_A_CITIZEN;
        return answer(context, result, entity.getName().getString() + " を、船の席へ向かわせます。");
    }

    private static int leave(CommandContext<CommandSourceStack> context, boolean withGround) throws CommandSyntaxException {
        if (unavailable(context)) return 0;
        final Entity entity = EntityArgument.getEntity(context, "citizen");
        final BlockPos ground = withGround ? BlockPosArgument.getBlockPos(context, "ground") : null;
        final Result result = entity instanceof Mob mob ? Boarding.leave(mob, ground) : Result.NOT_A_CITIZEN;
        return answer(context, result, entity.getName().getString() + (withGround ? " を立たせて、指定の場所へ向かわせます。" : " を立たせます。"));
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (unavailable(context)) return 0;
        final Entity entity = EntityArgument.getEntity(context, "citizen");
        final String state = switch (Boarding.state(entity)) {
            case WALKING -> "席へ歩いています。";
            case SEATED -> "船の席に座っています。";
            case LEAVING -> "立って、指定の場所へ歩いています。";
            default -> "乗り込みの途中でも、座ってもいません。";
        };
        context.getSource().sendSuccess(() -> Component.literal(entity.getName().getString() + ":" + state), false);
        return 1;
    }

    private static int answer(CommandContext<CommandSourceStack> context, Result result, String done) {
        if (result == Result.OK) {
            context.getSource().sendSuccess(() -> Component.literal(done), true);
            return 1;
        }
        context.getSource().sendFailure(Component.literal(result.text));
        return 0;
    }
}
